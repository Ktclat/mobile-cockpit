package dev.cockpit.domain.bytes

import dev.cockpit.domain.AgentId
import dev.cockpit.domain.ConversationId
import dev.cockpit.domain.ConversationRevision
import dev.cockpit.domain.EgressAuthorityRef
import dev.cockpit.domain.OneTimeReplyNonce
import dev.cockpit.domain.PermissionAuthorityRef
import dev.cockpit.domain.QuestionId
import dev.cockpit.domain.QuestionVersion
import dev.cockpit.domain.RunId
import dev.cockpit.domain.RunVersion
import dev.cockpit.domain.SafetyEpoch
import dev.cockpit.domain.TaskId
import dev.cockpit.domain.ToolCallId
import dev.cockpit.domain.VerificationCriterionId
import dev.cockpit.domain.VerificationPlanId
import dev.cockpit.domain.credential.CredentialReference
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImmutableBytesTest {
    @Test
    fun defensivelyCopiesInputAndOutput() {
        val input = byteArrayOf(1, 2, 3)
        val immutable = ImmutableBytes.copyOf(input)

        input[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), immutable.toByteArray())

        val exported = immutable.toByteArray()
        exported[1] = 8
        assertArrayEquals(byteArrayOf(1, 2, 3), immutable.toByteArray())
    }

    @Test
    fun allAccessibleByteArrayCreationPathsDefensivelyCopyAndKeepContentStable() {
        val creators = byteArrayCreators()

        assertTrue(creators.isNotEmpty())
        creators.forEach { create ->
            val input = byteArrayOf(1, 2, 3)
            val immutable = create(input)
            val expected = ImmutableBytes.copyOf(input)
            val initialHashCode = immutable.hashCode()

            input[0] = 9

            assertArrayEquals(byteArrayOf(1, 2, 3), immutable.toByteArray())
            assertEquals(expected, immutable)
            assertEquals(initialHashCode, immutable.hashCode())
        }
    }

    @Test
    fun usesContentEqualityAndHashCode() {
        val first = ImmutableBytes.copyOf(byteArrayOf(1, 2))
        val equal = ImmutableBytes.copyOf(byteArrayOf(1, 2))
        val different = ImmutableBytes.copyOf(byteArrayOf(2, 1))

        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, different)
    }

    @Test
    fun supportsEmptyBytes() {
        val empty = ImmutableBytes.copyOf(byteArrayOf())

        assertEquals(0, empty.size)
        assertArrayEquals(byteArrayOf(), empty.toByteArray())
    }

    @Test
    fun hasNoPublicMutableBackingFieldOrPlatformApi() {
        val type = ImmutableBytes::class.java
        val companionType = type.getField("Companion").type
        val forbiddenApiPrefixes = listOf(
            "android.",
            "java.io.",
            "java.nio.",
            "java.util.",
            "kotlin.sequences.",
            "kotlinx.",
        )
        val forbiddenApiTypes = setOf("java.lang.Iterable", "kotlin.jvm.internal.DefaultConstructorMarker")
        fun isForbiddenApiType(type: Class<*>): Boolean =
            type.name in forbiddenApiTypes || forbiddenApiPrefixes.any(type.name::startsWith)

        assertTrue(
            type.declaredFields.none { field ->
                Modifier.isPublic(field.modifiers) && field.type == ByteArray::class.java
            },
        )
        assertFalse(type.constructors.any { constructor ->
            constructor.parameterTypes.any(::isForbiddenApiType)
        })
        assertFalse((type.declaredMethods + companionType.declaredMethods).any { method ->
            Modifier.isPublic(method.modifiers) &&
                method.name != "toByteArray" &&
                (isForbiddenApiType(method.returnType) || method.parameterTypes.any(::isForbiddenApiType))
        })
    }

    @Test
    fun exposesOnlyFrozenPlainIdentifiersAndLogicalCredentialReference() {
        assertEquals("agent", AgentId("agent").value)
        assertEquals("conversation", ConversationId("conversation").value)
        assertEquals("task", TaskId("task").value)
        assertEquals("run", RunId("run").value)
        assertEquals("tool-call", ToolCallId("tool-call").value)
        assertEquals("permission", PermissionAuthorityRef("permission").value)
        assertEquals("egress", EgressAuthorityRef("egress").value)
        assertEquals("question", QuestionId("question").value)
        assertEquals(1L, ConversationRevision(1L).value)
        assertEquals(2L, RunVersion(2L).value)
        assertEquals(3L, QuestionVersion(3L).value)
        assertEquals("nonce", OneTimeReplyNonce("nonce").value)
        assertEquals(4L, SafetyEpoch(4L).value)
        assertEquals("plan", VerificationPlanId("plan").value)
        assertEquals("criterion", VerificationCriterionId("criterion").value)
        assertEquals("credential", CredentialReference("credential").value)
    }

    private fun byteArrayCreators(): List<(ByteArray) -> ImmutableBytes> {
        val type = ImmutableBytes::class.java
        val constructorCreators = type.constructors
            .filter { constructor -> constructor.parameterTypes.any { it == ByteArray::class.java } }
            .map { constructor ->
                { input: ByteArray ->
                    val arguments = constructor.parameterTypes.map { parameter ->
                        if (parameter == ByteArray::class.java) input else null
                    }.toTypedArray()
                    constructor.newInstance(*arguments) as ImmutableBytes
                }
            }
        val companionCreators = ImmutableBytes.Companion.javaClass.declaredMethods
            .filter { method ->
                Modifier.isPublic(method.modifiers) &&
                    method.returnType == ImmutableBytes::class.java &&
                    method.parameterTypes.contentEquals(arrayOf(ByteArray::class.java))
            }
            .map { method ->
                { input: ByteArray ->
                    method.invoke(ImmutableBytes.Companion, *arrayOf(input)) as ImmutableBytes
                }
            }

        return constructorCreators + companionCreators
    }
}
