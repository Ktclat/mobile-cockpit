package dev.cockpit.provider.api

import dev.cockpit.domain.prompt.PromptPlan

enum class ProviderMessageRole { SYSTEM, USER, ASSISTANT }

data class ProviderMessage(
    val role: ProviderMessageRole,
    val text: String,
)

data class NormalizedProviderRequest(
    val invocationId: ProviderInvocationId,
    val profile: ProviderProfile,
    val promptPlan: PromptPlan,
    val messages: List<ProviderMessage>,
    val maxOutputTokens: Int = profile.maxOutputTokens,
) {
    init {
        require(profile.model.isNotBlank()) { "A model is required for generation" }
        require(maxOutputTokens in 1..131_072) { "Max output tokens are out of range" }
    }
}
