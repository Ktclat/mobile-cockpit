package dev.cockpit.application

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.agent.Agent
import dev.cockpit.domain.agent.AgentCapabilities
import dev.cockpit.domain.agent.AgentImportSource
import dev.cockpit.domain.agent.Persona
import dev.cockpit.domain.ids.IdGenerator
import dev.cockpit.persistence.api.AgentPersistenceState
import dev.cockpit.persistence.api.AgentRepository
import dev.cockpit.persistence.api.ArchiveState

data class CreateAgentCommand(
    val persona: Persona,
    val capabilities: AgentCapabilities,
    val providerProfileId: String? = null,
    val providerModelId: String? = null,
    val importSource: AgentImportSource? = null,
)

class CreateAgent(
    private val repository: AgentRepository,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(command: CreateAgentCommand): Agent {
        val agent = Agent(AgentId(ids.nextId()), command.persona, command.capabilities)
        repository.saveConfiguration(
            AgentPersistenceState(
                agent = agent,
                personaId = agent.id.value,
                revision = 1L,
                archiveState = ArchiveState.ACTIVE,
                importSource = command.importSource,
            ),
            command.providerProfileId,
            command.providerModelId,
        )
        return agent
    }
}
