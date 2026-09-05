package dev.cockpit.application

import dev.cockpit.domain.agent.AgentDefinition
import dev.cockpit.domain.agent.AgentMode
import dev.cockpit.domain.agent.LorebookEntry
import dev.cockpit.domain.agent.LorebookPosition
import dev.cockpit.domain.agent.Persona
import dev.cockpit.domain.agent.editableDefinition
import dev.cockpit.domain.prompt.ConservativeTokenEstimator
import dev.cockpit.domain.prompt.PromptMessage
import dev.cockpit.domain.prompt.PromptMessageRole
import dev.cockpit.domain.prompt.PromptPlan
import dev.cockpit.domain.prompt.TokenEstimator

object AgentPromptPlanner {
    const val DEFAULT_USER_NAME = "User"

    fun defaultSystemPrompt(mode: AgentMode): String = when (mode) {
        AgentMode.ASSISTANT ->
            "You are {{char}}, a focused personal assistant. Follow the user's goal, " +
                "state uncertainty honestly, and never claim capabilities you do not have."
        AgentMode.ROLEPLAY ->
            "You are {{char}}. Stay consistent with the supplied character definition, " +
                "respond naturally in character, and do not invent tool access or permissions."
    }

    fun build(
        persona: Persona,
        userName: String = DEFAULT_USER_NAME,
        conversationText: List<String> = emptyList(),
        tokenEstimator: TokenEstimator = ConservativeTokenEstimator,
    ): PromptPlan {
        if (persona.definition == null) {
            val legacy = "You are ${persona.identity}. Presentation: ${persona.presentation}. " +
                "Voice: ${persona.voice}. Behavior: ${persona.behavioralTendency}. " +
                "Reply style: ${persona.promptStyle}."
            return PromptPlan(
                systemInstructions = listOf(legacy),
                estimatedInputTokens = tokenEstimator.estimate(legacy),
                activeLorebookEntryIds = emptyList(),
                notices = listOf("Legacy Agent settings are preserved."),
            )
        }

        val definition = persona.editableDefinition()
        val characterName = definition.nickname.ifBlank { definition.name }
        val defaultPrompt = defaultSystemPrompt(definition.mode)
        val selectedPrompt = definition.systemPrompt.ifBlank { defaultPrompt }
            .replace(ORIGINAL_MACRO, defaultPrompt)
        val postHistory = definition.postHistoryInstructions
            .replace(ORIGINAL_MACRO, "")
        val lorebook = matchLorebook(definition, conversationText, tokenEstimator)

        val sections = buildList {
            add(renderMacros(selectedPrompt, characterName, userName))
            lorebook.before.forEach { add(section("Relevant context", renderMacros(it.content, characterName, userName))) }
            if (definition.description.isNotBlank()) {
                add(section("Character definition", renderMacros(definition.description, characterName, userName)))
            }
            if (definition.personality.isNotBlank()) {
                add(section("Personality and voice", renderMacros(definition.personality, characterName, userName)))
            }
            if (definition.scenario.isNotBlank()) {
                add(section("Current scenario", renderMacros(definition.scenario, characterName, userName)))
            }
            lorebook.after.forEach { add(section("Relevant context", renderMacros(it.content, characterName, userName))) }
        }
        val assembled = sections.filter(String::isNotBlank).joinToString("\n\n")
        val fewShot = parseExampleDialogue(
            renderMacros(definition.exampleDialogue, characterName, userName),
            characterName,
            userName,
        )
        val renderedPostHistory = renderMacros(postHistory, characterName, userName).trim()
        val estimated = estimateTokens(
            texts = listOf(assembled) + fewShot.map(PromptMessage::text) + renderedPostHistory,
            tokenEstimator = tokenEstimator,
        )
        val notices = buildList {
            addAll(lorebook.notices)
            if (estimated > MAX_ESTIMATED_FIXED_TOKENS) {
                add("The fixed Agent definition is very large and may exceed some model contexts.")
            }
        }
        return PromptPlan(
            systemInstructions = listOfNotNull(assembled.takeIf(String::isNotBlank)),
            fewShotMessages = fewShot,
            postHistoryInstructions = listOfNotNull(renderedPostHistory.takeIf(String::isNotBlank)),
            estimatedInputTokens = estimated,
            activeLorebookEntryIds = (lorebook.before + lorebook.after).map(LorebookEntry::id),
            notices = notices,
        )
    }

    fun renderDialogueText(
        text: String,
        definition: AgentDefinition,
        userName: String = DEFAULT_USER_NAME,
    ): String = renderMacros(
        text = text,
        characterName = definition.nickname.ifBlank { definition.name },
        userName = userName,
    )

    private fun renderMacros(text: String, characterName: String, userName: String): String =
        text.replace(CHAR_MACRO, characterName)
            .replace(USER_MACRO, userName)
            .replace(BOT_ALIAS, characterName)
            .replace(USER_ALIAS, userName)

    private fun section(title: String, content: String): String = "[$title]\n$content"

    private fun matchLorebook(
        definition: AgentDefinition,
        conversationText: List<String>,
        tokenEstimator: TokenEstimator,
    ): LorebookMatch {
        val scanDepth = definition.lorebookScanDepth.coerceIn(1, 100)
        val haystack = conversationText.takeLast(scanDepth).joinToString("\n")
        val candidates = definition.lorebookEntries.withIndex()
            .filter { (_, entry) -> entry.enabled && entry.content.isNotBlank() }
            .filter { (_, entry) -> entry.constant || entry.matches(haystack) }
            .sortedWith(
                compareByDescending<IndexedValue<LorebookEntry>> { it.value.priority }
                    .thenBy { it.index },
            )

        val budget = definition.lorebookTokenBudget.coerceIn(0, 32_768)
        var used = 0
        val selected = mutableListOf<IndexedValue<LorebookEntry>>()
        var skippedForBudget = 0
        candidates.forEach { candidate ->
            val cost = tokenEstimator.estimate(candidate.value.content).coerceAtLeast(0)
            if (cost <= budget - used) {
                selected += candidate
                used += cost
            } else {
                skippedForBudget += 1
            }
        }
        val ordered = selected.sortedWith(
            compareBy<IndexedValue<LorebookEntry>> { it.value.insertionOrder }
                .thenBy { it.index },
        ).map { it.value }
        return LorebookMatch(
            before = ordered.filter { it.position == LorebookPosition.BEFORE_CHARACTER },
            after = ordered.filter { it.position == LorebookPosition.AFTER_CHARACTER },
            notices = if (skippedForBudget == 0) emptyList() else {
                listOf("$skippedForBudget lorebook entries were omitted by the Agent budget.")
            },
        )
    }

    private fun LorebookEntry.matches(haystack: String): Boolean {
        if (keywords.isEmpty()) return false
        fun contains(candidate: String): Boolean = if (caseSensitive) {
            haystack.contains(candidate)
        } else {
            haystack.contains(candidate, ignoreCase = true)
        }
        val primary = keywords.filter(String::isNotBlank).any(::contains)
        if (!primary) return false
        return !selective || secondaryKeywords.filter(String::isNotBlank).any(::contains)
    }

    private data class LorebookMatch(
        val before: List<LorebookEntry>,
        val after: List<LorebookEntry>,
        val notices: List<String>,
    )

    private fun estimateTokens(
        texts: List<String>,
        tokenEstimator: TokenEstimator,
    ): Int {
        var total = 0L
        texts.forEach { text ->
            total = (total + tokenEstimator.estimate(text).coerceAtLeast(0).toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
        }
        return total.toInt()
    }

    private fun parseExampleDialogue(
        rendered: String,
        characterName: String,
        userName: String,
    ): List<PromptMessage> {
        if (rendered.isBlank()) return emptyList()
        val messages = mutableListOf<PromptMessage>()
        var currentRole: PromptMessageRole? = null
        val currentText = StringBuilder()

        fun flush() {
            val text = currentText.toString().trim()
            val role = currentRole
            if (role != null && text.isNotEmpty()) messages += PromptMessage(role, text)
            currentText.clear()
        }

        rendered.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.trim().equals("<START>", ignoreCase = true)) {
                flush()
                currentRole = null
                return@forEach
            }
            val match = SPEAKER_LINE.matchEntire(line.trimStart())
            val role = match?.groupValues?.get(1)?.trim()?.let { speaker ->
                when {
                    speaker.equals(userName, ignoreCase = true) -> PromptMessageRole.USER
                    speaker.equals(characterName, ignoreCase = true) -> PromptMessageRole.ASSISTANT
                    else -> null
                }
            }
            if (role != null) {
                flush()
                currentRole = role
                currentText.append(match.groupValues[2])
            } else if (line.isNotBlank()) {
                if (currentRole == null) currentRole = PromptMessageRole.USER
                if (currentText.isNotEmpty()) currentText.append('\n')
                currentText.append(line)
            }
        }
        flush()
        return messages
    }

    private val ORIGINAL_MACRO = Regex(Regex.escape("{{original}}"), RegexOption.IGNORE_CASE)
    private val CHAR_MACRO = Regex(Regex.escape("{{char}}"), RegexOption.IGNORE_CASE)
    private val USER_MACRO = Regex(Regex.escape("{{user}}"), RegexOption.IGNORE_CASE)
    private val BOT_ALIAS = Regex("<BOT>", RegexOption.IGNORE_CASE)
    private val USER_ALIAS = Regex("<USER>", RegexOption.IGNORE_CASE)
    private val SPEAKER_LINE = Regex("^([^:\\n]{1,120}):\\s*(.*)$")
    private const val MAX_ESTIMATED_FIXED_TOKENS = 24_000
}
