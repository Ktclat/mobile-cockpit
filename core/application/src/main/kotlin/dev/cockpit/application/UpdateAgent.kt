package dev.cockpit.application

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.agent.AgentCapabilities
import dev.cockpit.domain.agent.AgentImportSource
import dev.cockpit.domain.agent.Persona
import dev.cockpit.persistence.api.AgentRepository

data class UpdateAgentCommand(
    val id: AgentId,
    val persona: Persona,
    val capabilities: AgentCapabilities,
    val providerProfileId: String?,
    val providerModelId: String? = null,
    val importSource: AgentImportSource? = null,
)

class UpdateAgent(private val repository: AgentRepository) {
    suspend operator fun invoke(command: UpdateAgentCommand): Boolean {
        val current = repository.load(command.id) ?: return false
        repository.saveConfiguration(
            state = current.copy(
                agent = current.agent.copy(
                    persona = command.persona,
                    capabilities = command.capabilities,
                ),
                revision = current.revision + 1L,
                importSource = command.importSource,
            ),
            providerProfileId = command.providerProfileId,
            providerModelId = command.providerModelId,
        )
        return true
    }
}
