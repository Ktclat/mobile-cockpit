package dev.cockpit.domain.agent

enum class AgentMode { ASSISTANT, ROLEPLAY }

enum class LorebookPosition { BEFORE_CHARACTER, AFTER_CHARACTER }

data class LorebookEntry(
    val id: String,
    val title: String = "",
    val keywords: List<String> = emptyList(),
    val secondaryKeywords: List<String> = emptyList(),
    val content: String = "",
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val caseSensitive: Boolean = false,
    val selective: Boolean = false,
    val insertionOrder: Int = 100,
    val position: LorebookPosition = LorebookPosition.AFTER_CHARACTER,
    val priority: Int = 0,
)

data class AgentDefinition(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val mode: AgentMode = AgentMode.ASSISTANT,
    val name: String,
    val nickname: String = "",
    val summary: String = "",
    val avatarRef: String? = null,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMessage: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val exampleDialogue: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val characterVersion: String = "",
    val creatorNotes: String = "",
    val lorebookEntries: List<LorebookEntry> = emptyList(),
    val lorebookScanDepth: Int = 8,
    val lorebookTokenBudget: Int = 1_024,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

data class AgentImportSource(
    val sourceFileName: String?,
    val payloadDigest: String,
    val detectedContainer: String,
    val detectedSpec: String,
    val originalJson: String,
    val warnings: List<String> = emptyList(),
    val preservedFieldCount: Int = 0,
    val downgraded: Boolean = false,
)

fun Persona.editableDefinition(): AgentDefinition = definition ?: AgentDefinition(
    schemaVersion = 1,
    mode = AgentMode.ASSISTANT,
    name = identity,
    summary = presentation.takeUnless { it == "Local Agent" }.orEmpty(),
    description = presentation.takeUnless { it == "Local Agent" }.orEmpty(),
    personality = listOf(voice, behavioralTendency)
        .filterNot { it.isBlank() || it == "Clear" || it == "Helpful" }
        .joinToString("\n"),
    systemPrompt = promptStyle.takeUnless { it == "Conversation" }.orEmpty(),
)
