package dev.cockpit.domain.prompt

enum class PromptMessageRole { USER, ASSISTANT }

data class PromptMessage(
    val role: PromptMessageRole,
    val text: String,
)

/** Protocol-neutral prompt sections. Protocol adapters decide how each section is rendered. */
data class PromptPlan(
    val systemInstructions: List<String> = emptyList(),
    val fewShotMessages: List<PromptMessage> = emptyList(),
    val postHistoryInstructions: List<String> = emptyList(),
    val estimatedInputTokens: Int = 0,
    val activeLorebookEntryIds: List<String> = emptyList(),
    val notices: List<String> = emptyList(),
)
