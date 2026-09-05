package dev.cockpit.domain.prompt

fun interface TokenEstimator {
    fun estimate(text: String): Int
}

/**
 * A deliberately conservative fallback until a model-specific tokenizer is available.
 * CJK syllables/ideographs and emoji are not treated like four-character Latin tokens.
 */
object ConservativeTokenEstimator : TokenEstimator {
    override fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0L
        var latinLike = 0L
        var other = 0L
        text.codePoints().forEach { codePoint ->
            when {
                isCjk(codePoint) -> cjk += 1
                codePoint <= 0x7f -> latinLike += 1
                Character.isLetterOrDigit(codePoint) -> other += 1
                else -> other += 2
            }
        }
        val estimate = cjk + ceilDiv(latinLike, 4L) + ceilDiv(other, 2L)
        return estimate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun isCjk(codePoint: Int): Boolean =
        codePoint in 0x2E80..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x20000..0x323AF ||
            codePoint in 0x3040..0x30FF ||
            codePoint in 0x31F0..0x31FF ||
            codePoint in 0xAC00..0xD7AF

    private fun ceilDiv(value: Long, divisor: Long): Long =
        if (value == 0L) 0L else 1L + (value - 1L) / divisor
}
