package dev.cockpit.architecture

import dev.cockpit.domain.ids.IdGenerator
import dev.cockpit.domain.time.AppClock
import dev.cockpit.domain.time.InstantValue
import dev.cockpit.runtime.DispatcherIdentity
import dev.cockpit.runtime.DispatcherLane
import dev.cockpit.runtime.DispatcherProvider
import dev.cockpit.testing.DeterministicIds
import dev.cockpit.testing.FakeClock
import dev.cockpit.testing.TestDispatcherProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.stream.Collectors
import kotlin.io.path.extension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InjectedSourcePolicyTest {
    @Test
    fun domainRuntimeUseOnlyInjectedClockIdsAndDispatchers() {
        val clock: AppClock = FakeClock(InstantValue(10))
        assertEquals(InstantValue(10), clock.now())

        val ids: IdGenerator = DeterministicIds("first-id", "second-id")
        assertEquals("first-id", ids.nextId())
        assertEquals("second-id", ids.nextId())

        val dispatcher: DispatcherProvider = TestDispatcherProvider(
            DispatcherLane("coordinator") to DispatcherIdentity("serial-test-lane"),
        )
        assertEquals(
            DispatcherIdentity("serial-test-lane"),
            dispatcher.dispatcherFor(DispatcherLane("coordinator")),
        )

        SourcePolicy.assertCompliant(projectRoot().let(::productionSourceRoots))
    }

    @Test
    fun sourcePolicyRejectsDirectSourcesAndAcceptsInjectedPorts() {
        val fixtureRoot = Files.createTempDirectory("cockpit-injected-source-policy-")
        try {
            writeFixture(
                fixtureRoot,
                "domain/ForbiddenTime.kt",
                """
                package dev.cockpit.domain
                fun capturedAt() = System.currentTimeMillis()
                """.trimIndent(),
            )
            writeFixture(
                fixtureRoot,
                "runtime/ForbiddenIds.kt",
                """
                package dev.cockpit.runtime
                import java.util.UUID
                fun generatedId() = UUID.randomUUID()
                """.trimIndent(),
            )
            writeFixture(
                fixtureRoot,
                "runtime/ForbiddenDispatcher.kt",
                """
                package dev.cockpit.runtime
                import kotlinx.coroutines.Dispatchers
                val dispatcher = Dispatchers.Default
                """.trimIndent(),
            )
            writeFixture(
                fixtureRoot,
                "domain/InjectedPorts.kt",
                """
                package dev.cockpit.domain
                import dev.cockpit.domain.time.AppClock
                // System.currentTimeMillis() belongs in an adapter, not here.
                fun currentTimeMillisFromClock(clock: AppClock) = clock.now()
                val diagnostic = "UUID.randomUUID()"
                """.trimIndent(),
            )

            val violation = assertThrows(AssertionError::class.java) {
                SourcePolicy.assertCompliant(listOf(fixtureRoot))
            }
            assertTrue(violation.message.orEmpty().contains("ForbiddenTime.kt"))
            assertTrue(violation.message.orEmpty().contains("ForbiddenIds.kt"))
            assertTrue(violation.message.orEmpty().contains("ForbiddenDispatcher.kt"))
            assertFalse(violation.message.orEmpty().contains("InjectedPorts.kt"))
        } finally {
            Files.walk(fixtureRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun projectRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve(".git")) }

    private fun productionSourceRoots(root: Path): List<Path> = listOf(
        root.resolve("core/domain/src/main/kotlin"),
        root.resolve("core/domain/src/main/java"),
        root.resolve("core/runtime-api/src/main/kotlin"),
        root.resolve("core/runtime-api/src/main/java"),
        root.resolve("core/runtime/src/main/kotlin"),
        root.resolve("core/runtime/src/main/java"),
    ).filter(Files::exists)

    private fun writeFixture(root: Path, relativePath: String, source: String) {
        val path = root.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, source)
    }

    private object SourcePolicy {
        fun assertCompliant(sourceRoots: List<Path>) {
            val violations = sourceRoots.flatMap { sourceRoot ->
                Files.walk(sourceRoot).use { paths ->
                    paths.filter { Files.isRegularFile(it) && it.extension in sourceExtensions }
                        .flatMap { sourceFile -> violationsIn(sourceFile).stream() }
                        .collect(Collectors.toList())
                }
            }
            assertTrue(violations.isEmpty()) {
                "Domain/Runtime production sources must use injected clock, ID, and dispatcher ports. Violations:\n" +
                    violations.joinToString("\n")
            }
        }

        private fun violationsIn(sourceFile: Path): List<String> {
            val source = stripCommentsAndStrings(Files.readString(sourceFile))
            val imports = source.lineSequence()
                .map(String::trim)
                .mapNotNull(importDeclaration::matchEntire)
                .associate { match ->
                    val importedName = match.groupValues[1]
                    val localName = match.groupValues[2].ifBlank { importedName.substringAfterLast('.') }
                    localName to importedName
                }

            return forbiddenPatterns.filter { forbidden ->
                forbidden.matches(source, imports)
            }.map { forbidden -> "$sourceFile: ${forbidden.description}" }
        }

        private fun stripCommentsAndStrings(source: String): String {
            val withoutStrings = source.replace(Regex("\"(?:\\\\.|[^\"\\\\])*\""), "\"\"")
            val withoutBlockComments = withoutStrings.replace(Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)), "")
            val withoutLineComments = withoutBlockComments.replace(Regex("//[^\\r\\n]*"), "")
            return withoutLineComments
        }

        private data class ForbiddenPattern(
            val description: String,
            val qualified: Regex,
            val importedType: String? = null,
            val simple: Regex? = null,
        ) {
            fun matches(source: String, imports: Map<String, String>): Boolean =
                qualified.containsMatchIn(source) ||
                    (importedType != null && imports.any { (localName, importedName) ->
                        importedName == importedType &&
                            simple?.let { Regex(it.pattern.replace("TYPE", Regex.escape(localName))) }
                                ?.containsMatchIn(source) == true
                    })
        }

        private val sourceExtensions = setOf("kt", "java")
        private val importDeclaration = Regex("^import\\s+([A-Za-z_][\\w.]*)(?:\\s+as\\s+([A-Za-z_]\\w*))?$")
        private val forbiddenPatterns = listOf(
            ForbiddenPattern("direct System.currentTimeMillis()", Regex("\\bSystem\\s*\\.\\s*currentTimeMillis\\s*\\(")),
            ForbiddenPattern("direct System.nanoTime()", Regex("\\bSystem\\s*\\.\\s*nanoTime\\s*\\(")),
            ForbiddenPattern(
                "direct java.time.Instant.now()",
                Regex("\\bjava\\s*\\.\\s*time\\s*\\.\\s*Instant\\s*\\.\\s*now\\s*\\("),
                "java.time.Instant",
                Regex("\\bTYPE\\s*\\.\\s*now\\s*\\("),
            ),
            ForbiddenPattern(
                "direct java.util.UUID.randomUUID()",
                Regex("\\bjava\\s*\\.\\s*util\\s*\\.\\s*UUID\\s*\\.\\s*randomUUID\\s*\\("),
                "java.util.UUID",
                Regex("\\bTYPE\\s*\\.\\s*randomUUID\\s*\\("),
            ),
            ForbiddenPattern(
                "direct kotlin.random.Random.Default",
                Regex("\\bkotlin\\s*\\.\\s*random\\s*\\.\\s*Random\\s*\\.\\s*Default\\b"),
                "kotlin.random.Random",
                Regex("\\bTYPE\\s*\\.\\s*Default\\b"),
            ),
            ForbiddenPattern(
                "direct kotlinx.coroutines.Dispatchers lane",
                Regex("\\bkotlinx\\s*\\.\\s*coroutines\\s*\\.\\s*Dispatchers\\s*\\.\\s*(?:Default|IO|Main|Unconfined)\\b"),
                "kotlinx.coroutines.Dispatchers",
                Regex("\\bTYPE\\s*\\.\\s*(?:Default|IO|Main|Unconfined)\\b"),
            ),
            ForbiddenPattern(
                "direct kotlinx.coroutines.GlobalScope",
                Regex("\\bkotlinx\\s*\\.\\s*coroutines\\s*\\.\\s*GlobalScope\\b"),
                "kotlinx.coroutines.GlobalScope",
                Regex("\\bTYPE\\b"),
            ),
        )
    }
}
