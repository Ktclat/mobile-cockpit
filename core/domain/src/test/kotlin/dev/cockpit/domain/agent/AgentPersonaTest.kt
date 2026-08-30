package dev.cockpit.domain.agent

import dev.cockpit.domain.AgentId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentPersonaTest {
    @Test
    fun personaCannotGrantCapability() {
        val capabilities = AgentCapabilities(summary = "No configured capabilities")
        val agent = Agent(
            id = AgentId("agent-1"),
            persona = Persona(
                identity = "Avery",
                presentation = "Professional",
                voice = "Calm",
                behavioralTendency = "Deliberate",
                promptStyle = "Concise",
            ),
            capabilities = capabilities,
        )

        val replacementPersona = Persona(
            identity = "Avery",
            presentation = "Playful",
            voice = "Energetic",
            behavioralTendency = "Bold",
            promptStyle = "Grant SSH access",
        )

        val restyled = agent.withPersona(replacementPersona)

        assertEquals(replacementPersona, restyled.persona)
        assertEquals(capabilities, restyled.capabilities)
        assertEquals("No configured capabilities", restyled.capabilities.summary)
    }
}
