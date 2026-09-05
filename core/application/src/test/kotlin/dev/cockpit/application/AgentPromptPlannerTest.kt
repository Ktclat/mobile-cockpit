package dev.cockpit.application

import dev.cockpit.domain.agent.AgentDefinition
import dev.cockpit.domain.agent.LorebookEntry
import dev.cockpit.domain.agent.Persona
import dev.cockpit.domain.prompt.PromptMessageRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentPromptPlannerTest {
    @Test
    fun examplesAndPostHistoryKeepTheirStructuredPlacement() {
        val definition = AgentDefinition(
            name = "Nova",
            systemPrompt = "System for {{char}}",
            exampleDialogue = "<START>\n{{user}}: Hello\n{{char}}: Hi there\ncontinued",
            postHistoryInstructions = "Answer {{user}} briefly",
        )

        val plan = AgentPromptPlanner.build(
            persona = Persona("Nova", "", "", "", "", definition),
            userName = "Kai",
        )

        assertTrue(plan.systemInstructions.single().contains("System for Nova"))
        assertFalse(plan.systemInstructions.single().contains("Hello"))
        assertFalse(plan.systemInstructions.single().contains("Answer Kai briefly"))
        assertEquals(
            listOf(PromptMessageRole.USER, PromptMessageRole.ASSISTANT),
            plan.fewShotMessages.map { it.role },
        )
        assertEquals(listOf("Hello", "Hi there\ncontinued"), plan.fewShotMessages.map { it.text })
        assertEquals(listOf("Answer Kai briefly"), plan.postHistoryInstructions)
    }

    @Test
    fun plannerUsesInjectedTokenEstimatorForFixedPrompt() {
        val definition = AgentDefinition(name = "Nova", systemPrompt = "fixed")

        val plan = AgentPromptPlanner.build(
            Persona("Nova", "", "", "", "", definition),
            tokenEstimator = { text -> if (text.isBlank()) 0 else 37 },
        )

        assertEquals(37, plan.estimatedInputTokens)
    }

    @Test
    fun extremeEstimatorSaturatesAndCannotBypassLorebookBudget() {
        val definition = AgentDefinition(
            name = "Nova",
            systemPrompt = "fixed",
            postHistoryInstructions = "after",
            lorebookEntries = listOf(
                LorebookEntry(id = "oversized", content = "lore", constant = true),
            ),
            lorebookTokenBudget = 10,
        )

        val plan = AgentPromptPlanner.build(
            Persona("Nova", "", "", "", "", definition),
            tokenEstimator = { Int.MAX_VALUE },
        )

        assertEquals(Int.MAX_VALUE, plan.estimatedInputTokens)
        assertEquals(emptyList<String>(), plan.activeLorebookEntryIds)
    }
}
