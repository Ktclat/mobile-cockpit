package dev.cockpit.presentation

import dev.cockpit.application.api.AgentProfileInput
import dev.cockpit.domain.agent.AgentImportSource
import dev.cockpit.domain.agent.AgentMode
import dev.cockpit.domain.agent.LorebookEntry
import dev.cockpit.domain.agent.LorebookPosition
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class CharacterCardImportPreview(
    val profile: AgentProfileInput,
    val container: String,
    val spec: String,
    val warnings: List<String>,
    val preservedFieldCount: Int,
    val alternateGreetingCount: Int,
    val lorebookEntryCount: Int,
    val hasCustomSystemPrompt: Boolean,
    val avatarPngBytes: ByteArray?,
)

internal object TavernCharacterCardImporter {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        bytes: ByteArray,
        sourceFileName: String? = null,
    ): Result<CharacterCardImportPreview> = runCatching {
        require(bytes.isNotEmpty()) { "The selected character card is empty." }
        val extracted = if (bytes.lookLikeJson()) {
            require(bytes.size <= MAX_JSON_BYTES) { "The JSON character card is larger than 5 MiB." }
            ExtractedDocument(
                document = bytes.decodeUtf8().trimStart('\uFEFF').trim(),
                container = "JSON",
                detectedSpecHint = null,
                warnings = emptyList(),
                downgraded = false,
                avatar = null,
            )
        } else {
            extractPngCharacterJson(bytes)
        }
        val documentBytes = extracted.document.toByteArray(StandardCharsets.UTF_8)
        require(documentBytes.size <= MAX_JSON_BYTES) {
            "The decoded character metadata is larger than 5 MiB."
        }
        val root = json.parseToJsonElement(extracted.document).jsonObject
        val data = (root["data"] as? JsonObject) ?: root
        val name = data.string("name").trim()
        require(name.isNotEmpty()) { "The character card does not contain a name." }
        require(data.keys.any { it in RECOGNIZED_CHARACTER_FIELDS }) {
            "The JSON file is not a recognized character card."
        }

        val declaredSpec = root.string("spec").ifBlank { extracted.detectedSpecHint.orEmpty() }
        val declaredVersion = root.string("spec_version")
        val spec = when {
            declaredSpec.contains("v3", ignoreCase = true) -> "Character Card V3"
            declaredSpec.contains("v2", ignoreCase = true) -> "Character Card V2"
            root["data"] is JsonObject -> "Character Card V2"
            else -> "Tavern Card V1"
        }
        val warnings = buildList {
            addAll(extracted.warnings)
            if (declaredSpec.isNotBlank() &&
                !declaredSpec.contains("chara_card_v2", ignoreCase = true) &&
                !declaredSpec.contains("chara_card_v3", ignoreCase = true)
            ) {
                add("Unknown card specification '$declaredSpec'; recognizable fields can still be imported.")
            }
            if (declaredVersion.isNotBlank() && declaredVersion.toDoubleOrNull() == null) {
                add("The declared card version '$declaredVersion' is non-standard.")
            }
        }
        val extensions = data["extensions"] as? JsonObject
        val mode = when (extensions?.string("cockpit_mode")?.lowercase()) {
            "assistant" -> AgentMode.ASSISTANT
            else -> AgentMode.ROLEPLAY
        }
        val lorebook = parseLorebook(data["character_book"] as? JsonObject)
        val alternateGreetings = data.strings("alternate_greetings")
        val preservedFields = data.keys.count { it !in KNOWN_DATA_FIELDS } +
            root.keys.count { it !in KNOWN_ROOT_FIELDS && root !== data }
        val digest = root.toString().toByteArray(StandardCharsets.UTF_8).sha256()
        val importSource = AgentImportSource(
            sourceFileName = sourceFileName,
            payloadDigest = digest,
            detectedContainer = extracted.container,
            detectedSpec = spec,
            originalJson = extracted.document,
            warnings = warnings,
            preservedFieldCount = preservedFields,
            downgraded = extracted.downgraded,
        )
        val profile = AgentProfileInput(
            identity = name,
            mode = mode,
            nickname = extensions?.string("cockpit_nickname").orEmpty(),
            summary = extensions?.string("cockpit_summary").orEmpty()
                .ifBlank { data.string("description").lineSequence().firstOrNull().orEmpty().take(140) },
            description = data.string("description"),
            personality = data.string("personality"),
            scenario = data.string("scenario"),
            firstMessage = data.string("first_mes"),
            alternateGreetings = alternateGreetings,
            exampleDialogue = data.string("mes_example"),
            systemPrompt = data.string("system_prompt"),
            postHistoryInstructions = data.string("post_history_instructions"),
            tags = data.strings("tags"),
            creator = data.string("creator"),
            characterVersion = data.string("character_version"),
            creatorNotes = data.string("creator_notes"),
            lorebookEntries = lorebook.entries,
            lorebookScanDepth = lorebook.scanDepth,
            lorebookTokenBudget = lorebook.tokenBudget,
            importSource = importSource,
            capabilitySummary = "Imported character card",
        )
        CharacterCardImportPreview(
            profile = profile,
            container = extracted.container,
            spec = spec,
            warnings = warnings,
            preservedFieldCount = preservedFields,
            alternateGreetingCount = alternateGreetings.size,
            lorebookEntryCount = lorebook.entries.size,
            hasCustomSystemPrompt = profile.systemPrompt.isNotBlank() ||
                profile.postHistoryInstructions.isNotBlank(),
            avatarPngBytes = extracted.avatar,
        )
    }

    private fun parseLorebook(book: JsonObject?): ParsedLorebook {
        if (book == null) return ParsedLorebook(emptyList(), 8, 1_024)
        val entries = (book["entries"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val entry = element as? JsonObject ?: return@mapIndexedNotNull null
            val content = entry.string("content")
            if (content.isBlank()) return@mapIndexedNotNull null
            LorebookEntry(
                id = entry.string("id").ifBlank { "imported-$index" },
                title = entry.string("name").ifBlank { entry.string("comment") },
                keywords = entry.stringsOrSingle("keys").ifEmpty { entry.stringsOrSingle("key") },
                secondaryKeywords = entry.stringsOrSingle("secondary_keys"),
                content = content,
                enabled = entry.boolean("enabled", true),
                constant = entry.boolean("constant", false),
                caseSensitive = entry.boolean("case_sensitive", false),
                selective = entry.boolean("selective", false),
                insertionOrder = entry.int("insertion_order", entry.int("order", index * 10 + 100)),
                position = when (entry.string("position").lowercase()) {
                    "before_char", "before_character", "0" -> LorebookPosition.BEFORE_CHARACTER
                    else -> LorebookPosition.AFTER_CHARACTER
                },
                priority = entry.int("priority", 0),
            )
        }
        return ParsedLorebook(
            entries = entries,
            scanDepth = book.int("scan_depth", 8).coerceIn(1, 100),
            tokenBudget = book.int("token_budget", 1_024).coerceIn(0, 100_000),
        )
    }

    private fun extractPngCharacterJson(bytes: ByteArray): ExtractedDocument {
        require(bytes.size <= MAX_PNG_BYTES) { "The PNG character card is larger than 30 MiB." }
        require(bytes.size >= PNG_SIGNATURE.size && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
            "Choose a Tavern character card in PNG or JSON format."
        }
        val candidates = mutableMapOf<String, MutableList<String>>()
        var offset = 8
        var sawEnd = false
        while (offset + 12 <= bytes.size) {
            val length = bytes.readUInt32(offset)
            val dataStart = offset + 8
            val dataEndLong = dataStart.toLong() + length
            require(dataEndLong <= bytes.size.toLong() - 4L) { "The PNG character card is damaged." }
            val dataEnd = dataEndLong.toInt()
            val typeBytes = bytes.copyOfRange(offset + 4, offset + 8)
            val type = String(typeBytes, StandardCharsets.US_ASCII)
            val expectedCrc = bytes.readUInt32Long(dataEnd)
            val crc = CRC32().apply {
                update(typeBytes)
                update(bytes, dataStart, length)
            }.value
            require(crc == expectedCrc) { "The PNG character card failed its integrity check." }
            if (type == "tEXt") {
                val separator = (dataStart until dataEnd).firstOrNull { bytes[it].toInt() == 0 }
                if (separator != null) {
                    val keyword = String(
                        bytes,
                        dataStart,
                        separator - dataStart,
                        StandardCharsets.ISO_8859_1,
                    )
                    if (keyword == "chara" || keyword == "ccv3") {
                        val encoded = String(
                            bytes,
                            separator + 1,
                            dataEnd - separator - 1,
                            StandardCharsets.ISO_8859_1,
                        ).trim()
                        require(encoded.length <= MAX_BASE64_CHARACTERS) {
                            "The PNG character metadata is too large."
                        }
                        candidates.getOrPut(keyword) { mutableListOf() } += encoded
                    }
                }
            }
            offset = dataEnd + 4
            if (type == "IEND") {
                sawEnd = true
                break
            }
        }
        require(sawEnd) { "The PNG character card is truncated." }
        val v3 = candidates["ccv3"].orEmpty().decodeValidCandidates()
        val legacy = candidates["chara"].orEmpty().decodeValidCandidates()
        require(v3.distinct().size <= 1) { "The PNG contains conflicting ccv3 metadata blocks." }
        require(legacy.distinct().size <= 1) { "The PNG contains conflicting chara metadata blocks." }
        val selected: String
        val specHint: String
        val warnings = mutableListOf<String>()
        val downgraded: Boolean
        when {
            v3.isNotEmpty() -> {
                selected = v3.first()
                specHint = "chara_card_v3"
                downgraded = false
                if (legacy.isNotEmpty() && legacy.first() != selected) {
                    warnings += "Both ccv3 and legacy chara metadata are present; ccv3 takes precedence."
                }
            }
            legacy.isNotEmpty() -> {
                selected = legacy.first()
                specHint = "chara_card_v2"
                downgraded = candidates["ccv3"].orEmpty().isNotEmpty()
                if (downgraded) {
                    warnings += "The ccv3 block is invalid; valid legacy chara metadata will be used."
                }
            }
            else -> error("This PNG does not contain valid Tavern character metadata.")
        }
        return ExtractedDocument(
            document = selected.trimStart('\uFEFF').trim(),
            container = "PNG",
            detectedSpecHint = specHint,
            warnings = warnings,
            downgraded = downgraded,
            avatar = bytes.copyOf(),
        )
    }

    private fun List<String>.decodeValidCandidates(): List<String> = mapNotNull { encoded ->
        runCatching {
            val decoded = Base64.getDecoder().decode(encoded)
            require(decoded.size <= MAX_JSON_BYTES)
            val document = decoded.decodeUtf8().trimStart('\uFEFF').trim()
            val root = json.parseToJsonElement(document).jsonObject
            val data = (root["data"] as? JsonObject) ?: root
            require(data.string("name").isNotBlank())
            require(data.keys.any { it in RECOGNIZED_CHARACTER_FIELDS })
            root.toString()
        }.getOrNull()
    }

    private fun ByteArray.decodeUtf8(): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()

    private fun ByteArray.lookLikeJson(): Boolean {
        var index = 0
        if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()) {
            index = 3
        }
        while (index < size && this[index].toInt().toChar().isWhitespace()) index += 1
        return index < size && this[index].toInt().toChar() == '{'
    }

    private fun ByteArray.readUInt32(offset: Int): Int {
        require(offset >= 0 && offset + 4 <= size) { "The PNG character card is damaged." }
        val value =
            ((this[offset].toLong() and 0xff) shl 24) or
                ((this[offset + 1].toLong() and 0xff) shl 16) or
                ((this[offset + 2].toLong() and 0xff) shl 8) or
                (this[offset + 3].toLong() and 0xff)
        require(value <= Int.MAX_VALUE) { "The PNG character card is damaged." }
        return value.toInt()
    }

    private fun ByteArray.readUInt32Long(offset: Int): Long {
        require(offset >= 0 && offset + 4 <= size) { "The PNG character card is damaged." }
        return ((this[offset].toLong() and 0xff) shl 24) or
            ((this[offset + 1].toLong() and 0xff) shl 16) or
            ((this[offset + 2].toLong() and 0xff) shl 8) or
            (this[offset + 3].toLong() and 0xff)
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.stringsOrSingle(key: String): List<String> = when (val value = this[key]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> value.contentOrNull?.split(',')?.map(String::trim).orEmpty()
        else -> emptyList()
    }.filter(String::isNotBlank)

    private fun JsonObject.boolean(key: String, fallback: Boolean): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: fallback

    private fun JsonObject.int(key: String, fallback: Int): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: fallback

    private data class ExtractedDocument(
        val document: String,
        val container: String,
        val detectedSpecHint: String?,
        val warnings: List<String>,
        val downgraded: Boolean,
        val avatar: ByteArray?,
    )

    private data class ParsedLorebook(
        val entries: List<LorebookEntry>,
        val scanDepth: Int,
        val tokenBudget: Int,
    )

    private const val MAX_JSON_BYTES = 5 * 1024 * 1024
    private const val MAX_PNG_BYTES = 30 * 1024 * 1024
    private const val MAX_BASE64_CHARACTERS = (MAX_JSON_BYTES * 4 / 3) + 16
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )
    private val RECOGNIZED_CHARACTER_FIELDS = setOf(
        "name", "description", "personality", "scenario", "first_mes", "mes_example",
    )
    private val KNOWN_ROOT_FIELDS = setOf("spec", "spec_version", "data")
    private val KNOWN_DATA_FIELDS = setOf(
        "name", "description", "personality", "scenario", "first_mes", "mes_example",
        "creator_notes", "system_prompt", "post_history_instructions", "alternate_greetings",
        "tags", "creator", "character_version", "character_book", "extensions",
    )
}
