package dev.cockpit.domain.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenEstimatorTest {
    @Test
    fun cjkUsesAConservativePerCharacterEstimate() {
        assertEquals(4, ConservativeTokenEstimator.estimate("你好世界"))
        assertEquals(1, ConservativeTokenEstimator.estimate("test"))
        assertTrue(
            ConservativeTokenEstimator.estimate("这是较长的中文文本") >
                ConservativeTokenEstimator.estimate("abcdefghij"),
        )
    }

    @Test
    fun supplementaryCjkCodePointCountsOnce() {
        assertEquals(1, ConservativeTokenEstimator.estimate("𠀀"))
    }
}
