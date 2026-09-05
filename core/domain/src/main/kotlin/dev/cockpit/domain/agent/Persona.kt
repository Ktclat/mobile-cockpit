package dev.cockpit.domain.agent

data class Persona(
    val identity: String,
    val presentation: String,
    val voice: String,
    val behavioralTendency: String,
    val promptStyle: String,
    val definition: AgentDefinition? = null,
)
