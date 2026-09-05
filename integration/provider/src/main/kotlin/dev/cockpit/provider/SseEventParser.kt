package dev.cockpit.provider

import okio.BufferedSource

internal data class SseEvent(val event: String?, val data: String)

internal class SseEventLimitException : IllegalStateException(
    "The provider sent an oversized streaming event.",
)

internal object SseEventParser {
    fun parse(source: BufferedSource): Sequence<SseEvent> = sequence {
        var event: String? = null
        val data = mutableListOf<String>()
        var dataCharacters = 0

        fun reset() {
            event = null
            data.clear()
            dataCharacters = 0
        }

        while (true) {
            val line = readBoundedLine(source)
            if (line == null || line.isEmpty()) {
                if (data.isNotEmpty()) yield(SseEvent(event, data.joinToString("\n")))
                reset()
                if (line == null) break
                continue
            }
            when {
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.removePrefix("event:").trimStart()
                line.startsWith("data:") -> {
                    val value = line.removePrefix("data:").trimStart()
                    if (dataCharacters > MAX_EVENT_CHARACTERS - value.length) {
                        throw SseEventLimitException()
                    }
                    dataCharacters += value.length
                    data += value
                }
            }
        }
    }

    private fun readBoundedLine(source: BufferedSource): String? {
        if (source.exhausted()) return null
        val newlineOffset = source.indexOf('\n'.code.toByte(), 0L, MAX_LINE_BYTES + 1L)
        if (newlineOffset >= 0L) {
            val line = source.readUtf8(newlineOffset)
            source.skip(1L)
            return line.removeSuffix("\r")
        }
        if (source.request(MAX_LINE_BYTES + 1L)) throw SseEventLimitException()
        return source.readUtf8().removeSuffix("\r")
    }

    private const val MAX_LINE_BYTES = 262_144L
    private const val MAX_EVENT_CHARACTERS = 1_048_576
}
