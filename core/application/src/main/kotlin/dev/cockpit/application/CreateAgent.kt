package dev.cockpit.application

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.agent.Agent
import dev.cockpit.domain.agent.AgentCapabilities
import dev.cockpit.domain.agent.Persona
import dev.cockpit.domain.ids.IdGenerator
import dev.cockpit.persistence.api.AgentPersistenceState
import dev.cockpit.persistence.api.AgentRepository
import dev.cockpit.persistence.api.ArchiveState

data class CreateAgentCommand(val persona: Persona, val capabilities: AgentCapabilities)

class CreateAgent(
    private val repository: AgentRepository,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(command: CreateAgentCommand): Agent {
        val agent = Agent(AgentId(ids.nextId()), command.persona, command.capabilities)
        repository.save(AgentPersistenceState(agent, agent.id.value, 0L, ArchiveState.ACTIVE))
        return agent
    }
}
