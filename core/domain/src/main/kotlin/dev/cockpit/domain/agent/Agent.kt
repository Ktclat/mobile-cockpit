package dev.cockpit.domain.agent

import dev.cockpit.domain.AgentId

data class Agent(
    val id: AgentId,
    val persona: Persona,
    val capabilities: AgentCapabilities,
) {
    fun withPersona(persona: Persona): Agent = copy(persona = persona)
}
