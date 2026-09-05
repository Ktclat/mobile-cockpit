package dev.cockpit.persistence.room

import dev.cockpit.domain.agent.AgentDefinition
import dev.cockpit.domain.agent.AgentMode
import dev.cockpit.domain.agent.LorebookEntry
import dev.cockpit.domain.agent.LorebookPosition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object AgentDefinitionJsonCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(definition: AgentDefinition): String = buildJsonObject {
        put("schemaVersion", definition.schemaVersion)
        put("mode", definition.mode.name)
        put("name", definition.name)
        put("nickname", definition.nickname)
        put("summary", definition.summary)
        definition.avatarRef?.let { put("avatarRef", it) }
        put("description", definition.description)
        put("personality", definition.personality)
        put("scenario", definition.scenario)
        put("firstMessage", definition.firstMessage)
        put("alternateGreetings", definition.alternateGreetings.toJsonArray())
        put("exampleDialogue", definition.exampleDialogue)
        put("systemPrompt", definition.systemPrompt)
        put("postHistoryInstructions", definition.postHistoryInstructions)
        put("tags", definition.tags.toJsonArray())
        put("creator", definition.creator)
        put("characterVersion", definition.characterVersion)
        put("creatorNotes", definition.creatorNotes)
        put("lorebookEntries", buildJsonArray {
            definition.lorebookEntries.forEach { entry ->
                add(buildJsonObject {
                    put("id", entry.id)
                    put("title", entry.title)
                    put("keywords", entry.keywords.toJsonArray())
                    put("secondaryKeywords", entry.secondaryKeywords.toJsonArray())
                    put("content", entry.content)
                    put("enabled", entry.enabled)
                    put("constant", entry.constant)
                    put("caseSensitive", entry.caseSensitive)
                    put("selective", entry.selective)
                    put("insertionOrder", entry.insertionOrder)
                    put("position", entry.position.name)
                    put("priority", entry.priority)
                })
            }
        })
        put("lorebookScanDepth", definition.lorebookScanDepth)
        put("lorebookTokenBudget", definition.lorebookTokenBudget)
    }.toString()

    fun decode(value: String?): AgentDefinition? = runCatching {
        if (value.isNullOrBlank()) return null
        val root = json.parseToJsonElement(value).jsonObject
        if (root.isEmpty() || !root.containsKey("name")) return null
        AgentDefinition(
            schemaVersion = root.int("schemaVersion", AgentDefinition.CURRENT_SCHEMA_VERSION),
            mode = root.enum("mode", AgentMode.ASSISTANT),
            name = root.string("name"),
            nickname = root.string("nickname"),
            summary = root.string("summary"),
            avatarRef = root.stringOrNull("avatarRef"),
            description = root.string("description"),
            personality = root.string("personality"),
            scenario = root.string("scenario"),
            firstMessage = root.string("firstMessage"),
            alternateGreetings = root.strings("alternateGreetings"),
            exampleDialogue = root.string("exampleDialogue"),
            systemPrompt = root.string("systemPrompt"),
            postHistoryInstructions = root.string("postHistoryInstructions"),
            tags = root.strings("tags"),
            creator = root.string("creator"),
            characterVersion = root.string("characterVersion"),
            creatorNotes = root.string("creatorNotes"),
            lorebookEntries = (root["lorebookEntries"] as? JsonArray).orEmpty().mapNotNull { element ->
                runCatching {
                    val entry = element.jsonObject
                    LorebookEntry(
                        id = entry.string("id").ifBlank { "lore-${entry.hashCode()}" },
                        title = entry.string("title"),
                        keywords = entry.strings("keywords"),
                        secondaryKeywords = entry.strings("secondaryKeywords"),
                        content = entry.string("content"),
                        enabled = entry.boolean("enabled", true),
                        constant = entry.boolean("constant", false),
                        caseSensitive = entry.boolean("caseSensitive", false),
                        selective = entry.boolean("selective", false),
                        insertionOrder = entry.int("insertionOrder", 100),
                        position = entry.enum("position", LorebookPosition.AFTER_CHARACTER),
                        priority = entry.int("priority", 0),
                    )
                }.getOrNull()
            },
            lorebookScanDepth = root.int("lorebookScanDepth", 8).coerceIn(1, 100),
            lorebookTokenBudget = root.int("lorebookTokenBudget", 1_024).coerceIn(0, 100_000),
        )
    }.getOrNull()

    fun encodeStrings(values: List<String>): String = values.toJsonArray().toString()

    fun decodeStrings(value: String?): List<String> = runCatching {
        if (value.isNullOrBlank()) return emptyList()
        json.parseToJsonElement(value).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
    }.getOrDefault(emptyList())

    private fun List<String>.toJsonArray() = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.stringOrNull(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.strings(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull {
        it.jsonPrimitive.contentOrNull
    }
    private fun JsonObject.int(key: String, fallback: Int) = this[key]?.jsonPrimitive?.intOrNull ?: fallback
    private fun JsonObject.boolean(key: String, fallback: Boolean) =
        this[key]?.jsonPrimitive?.booleanOrNull ?: fallback
    private inline fun <reified T : Enum<T>> JsonObject.enum(key: String, fallback: T): T =
        this[key]?.jsonPrimitive?.contentOrNull?.let { value ->
            enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
        } ?: fallback
}
