package dev.cockpit.application

import dev.cockpit.domain.agent.AgentDefinition
import dev.cockpit.domain.agent.AgentImportSource
import dev.cockpit.domain.agent.LorebookEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class CharacterCardExport(
    val json: String,
    val preservedOriginal: Boolean,
)

/**
 * Writes a Character Card V2-compatible document. When an Agent came from a card,
 * only fields understood by Cockpit are replaced so vendor extensions survive export.
 */
object CharacterCardJsonExporter {
    private val json = Json { prettyPrint = true }

    fun export(
        definition: AgentDefinition,
        source: AgentImportSource?,
    ): CharacterCardExport {
        val importedRoot = source?.originalJson
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val base = importedRoot ?: buildJsonObject {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            put("data", JsonObject(emptyMap()))
        }
        val hasDataEnvelope = base["data"] is JsonObject
        val existingData = (base["data"] as? JsonObject) ?: base
        val updatedData = JsonObject(existingData + knownFields(definition, existingData))
        val output = if (hasDataEnvelope) {
            JsonObject(base + ("data" to updatedData))
        } else {
            updatedData
        }
        return CharacterCardExport(
            json = json.encodeToString(JsonElement.serializer(), output),
            preservedOriginal = importedRoot != null,
        )
    }

    private fun knownFields(
        definition: AgentDefinition,
        existingData: JsonObject,
    ): Map<String, JsonElement> = buildMap {
        put("name", JsonPrimitive(definition.name))
        put("description", JsonPrimitive(definition.description))
        put("personality", JsonPrimitive(definition.personality))
        put("scenario", JsonPrimitive(definition.scenario))
        put("first_mes", JsonPrimitive(definition.firstMessage))
        put("mes_example", JsonPrimitive(definition.exampleDialogue))
        put("creator_notes", JsonPrimitive(definition.creatorNotes))
        put("system_prompt", JsonPrimitive(definition.systemPrompt))
        put("post_history_instructions", JsonPrimitive(definition.postHistoryInstructions))
        put("alternate_greetings", definition.alternateGreetings.asJsonArray())
        put("tags", definition.tags.asJsonArray())
        put("creator", JsonPrimitive(definition.creator))
        put("character_version", JsonPrimitive(definition.characterVersion))
        put(
            "character_book",
            characterBook(definition, existingData["character_book"] as? JsonObject),
        )
        val existingExtensions = existingData["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        put(
            "extensions",
            JsonObject(
                existingExtensions + mapOf(
                    "cockpit_mode" to JsonPrimitive(definition.mode.name.lowercase()),
                    "cockpit_summary" to JsonPrimitive(definition.summary),
                    "cockpit_nickname" to JsonPrimitive(definition.nickname),
                ),
            ),
        )
    }

    private fun characterBook(
        definition: AgentDefinition,
        existingBook: JsonObject?,
    ): JsonElement {
        if (definition.lorebookEntries.isEmpty() && existingBook == null) return JsonNull
        val oldEntries = existingBook?.get("entries") as? JsonArray ?: JsonArray(emptyList())
        val updates = mapOf<String, JsonElement>(
            "scan_depth" to JsonPrimitive(definition.lorebookScanDepth),
            "token_budget" to JsonPrimitive(definition.lorebookTokenBudget),
            "entries" to buildJsonArray {
                definition.lorebookEntries.forEachIndexed { index, entry ->
                    val old = oldEntries.firstOrNull { candidate ->
                        val objectValue = candidate as? JsonObject ?: return@firstOrNull false
                        objectValue["id"]?.toString()?.trim('"') == entry.id
                    } as? JsonObject ?: oldEntries.getOrNull(index) as? JsonObject
                    val fields = entry.toJsonFields(index)
                    val oldExtensions = old?.get("extensions") as? JsonObject ?: JsonObject(emptyMap())
                    val newExtensions = fields["extensions"] as? JsonObject ?: JsonObject(emptyMap())
                    add(
                        JsonObject(
                            (old ?: JsonObject(emptyMap())) + fields +
                                ("extensions" to JsonObject(oldExtensions + newExtensions)),
                        ),
                    )
                }
            },
        )
        return JsonObject((existingBook ?: JsonObject(emptyMap())) + updates)
    }

    private fun LorebookEntry.toJsonFields(index: Int): Map<String, JsonElement> = mapOf(
        "id" to JsonPrimitive(id),
        "name" to JsonPrimitive(title),
        "keys" to keywords.asJsonArray(),
        "secondary_keys" to secondaryKeywords.asJsonArray(),
        "content" to JsonPrimitive(content),
        "enabled" to JsonPrimitive(enabled),
        "constant" to JsonPrimitive(constant),
        "case_sensitive" to JsonPrimitive(caseSensitive),
        "selective" to JsonPrimitive(selective),
        "insertion_order" to JsonPrimitive(insertionOrder),
        "position" to JsonPrimitive(if (position.name == "BEFORE_CHARACTER") "before_char" else "after_char"),
        "priority" to JsonPrimitive(priority),
        "extensions" to buildJsonObject { put("cockpit_source_index", index) },
    )

    private fun List<String>.asJsonArray(): JsonArray =
        buildJsonArray { forEach { add(JsonPrimitive(it)) } }
}
