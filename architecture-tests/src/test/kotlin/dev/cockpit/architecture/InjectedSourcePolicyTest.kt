package dev.cockpit.architecture

import dev.cockpit.domain.ids.IdGenerator
import dev.cockpit.domain.time.AppClock
import dev.cockpit.domain.time.InstantValue
import dev.cockpit.runtime.DispatcherLane
import dev.cockpit.runtime.RuntimeDispatcher
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

        SourcePolicy.assertCompliant(projectRoot().let(::productionSourceRoots))
    }

    @Test
    fun testDispatcherQueuesWorkUntilTheTestRunsIt() {
        val lane = DispatcherLane("coordinator")
        val provider = TestDispatcherProvider(lane)
        val dispatcher: RuntimeDispatcher = provider.dispatcherFor(lane)
        var ran = false

        dispatcher.dispatch { ran = true }

        assertFalse(ran)
        assertEquals(listOf(lane), provider.queuedLanes())
        assertTrue(provider.runNext())
        assertTrue(ran)
        assertFalse(provider.runNext())
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

    @Test
    fun sourcePolicyRejectsKotlinAliasesAndMemberImports() = withFixtureRoot { fixtureRoot ->
        writeFixture(
            fixtureRoot,
            "domain/KotlinAliases.kt",
            """
            package dev.cockpit.domain
            import java.time.Clock as WallClock
            import java.time.Clock.systemUTC
            import java.util.UUID.randomUUID as freshId
            import kotlin.random.Random as SourceRandom
            import kotlinx.coroutines.Dispatchers as RuntimeDispatchers
            val clock = WallClock.systemDefaultZone()
            val inheritedClock = systemUTC()
            val id = freshId()
            val entropy = SourceRandom.Default.nextInt()
            val dispatcher = RuntimeDispatchers.IO
            """.trimIndent(),
        )

        assertPolicyRejects(
            fixtureRoot,
            "KotlinAliases.kt",
            "Clock.system*",
            "UUID.randomUUID",
            "kotlin.random.Random",
            "Dispatchers property",
        )
    }

    @Test
    fun sourcePolicyRejectsJavaStaticWildcardAndGetterImports() = withFixtureRoot { fixtureRoot ->
        writeFixture(
            fixtureRoot,
            "runtime/JavaImports.java",
            """
            package dev.cockpit.runtime;
            import java.time.*;
            import java.util.*;
            import java.security.SecureRandom;
            import kotlinx.coroutines.GlobalScope;
            import static java.lang.System.*;
            import static java.util.concurrent.ThreadLocalRandom.current;
            import static kotlinx.coroutines.Dispatchers.getDefault;
            class JavaImports {
                long wallClock = currentTimeMillis();
                Clock clock = Clock.systemUTC();
                UUID id = UUID.randomUUID();
                Random random = new Random();
                SecureRandom secureRandom = new SecureRandom();
                int entropy = current().nextInt();
                Object dispatcher = getDefault();
                Object global = GlobalScope.INSTANCE;
            }
            """.trimIndent(),
        )

        assertPolicyRejects(
            fixtureRoot,
            "JavaImports.java",
            "System.currentTimeMillis",
            "Clock.system*",
            "UUID.randomUUID",
            "java.util.Random",
            "SecureRandom",
            "ThreadLocalRandom",
            "Dispatchers getter",
            "GlobalScope",
        )
    }

    @Test
    fun sourcePolicyRejectsQualifiedJavaTimeEntropyAndDispatcherFamilies() = withFixtureRoot { fixtureRoot ->
        writeFixture(
            fixtureRoot,
            "runtime/QualifiedSources.java",
            """
            package dev.cockpit.runtime;
            class QualifiedSources {
                Object instant = java.time.Instant.now();
                Object clock = java.time.Clock.systemUTC();
                Object id = java.util.UUID.randomUUID();
                Object random = new java.util.Random();
                Object secureRandom = new java.security.SecureRandom();
                int entropy = java.util.concurrent.ThreadLocalRandom.current().nextInt();
                Object dispatcher = kotlinx.coroutines.Dispatchers.getIO();
                Object global = kotlinx.coroutines.GlobalScope.INSTANCE;
            }
            """.trimIndent(),
        )

        assertPolicyRejects(
            fixtureRoot,
            "QualifiedSources.java",
            "Instant.now",
            "Clock.system*",
            "UUID.randomUUID",
            "java.util.Random",
            "SecureRandom",
            "ThreadLocalRandom",
            "Dispatchers getter",
            "GlobalScope",
        )
    }

    @Test
    fun sourcePolicyPreservesExecutableTemplates() = withFixtureRoot { fixtureRoot ->
        writeFixture(
            fixtureRoot,
            "domain/KotlinTemplate.kt",
            """
            package dev.cockpit.domain
            fun capturedAt() = "${'$'}{System.currentTimeMillis()}"
            """.trimIndent(),
        )

        assertPolicyRejects(fixtureRoot, "KotlinTemplate.kt")
    }

    @Test
    fun sourcePolicyAcceptsQuotedCommentsNestedCommentsAndTextBlocks() = withFixtureRoot { fixtureRoot ->
        writeFixture(
            fixtureRoot,
            "domain/KotlinLexicalForms.kt",
            listOf(
                "package dev.cockpit.domain",
                "import dev.cockpit.domain.time.AppClock",
                "// \"${'$'}{System.currentTimeMillis()}\" is documentation only.",
                "/* outer comment",
                "   /* System.currentTimeMillis() */",
                "*/",
                "val literal = \"\"\"System.currentTimeMillis() UUID.randomUUID()\"\"\"",
                "fun capturedAt(clock: AppClock) = clock.now()",
            ).joinToString("\n"),
        )
        writeFixture(
            fixtureRoot,
            "runtime/JavaTextBlock.java",
            listOf(
                "package dev.cockpit.runtime;",
                "class JavaTextBlock {",
                "  String literal = \"\"\"",
                "    System.currentTimeMillis(); UUID.randomUUID();",
                "    \"\"\";",
                "}",
            ).joinToString("\n"),
        )

        SourcePolicy.assertCompliant(listOf(fixtureRoot))
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

    private fun withFixtureRoot(block: (Path) -> Unit) {
        val fixtureRoot = Files.createTempDirectory("cockpit-injected-source-policy-")
        try {
            block(fixtureRoot)
        } finally {
            Files.walk(fixtureRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun assertPolicyRejects(fixtureRoot: Path, expectedFileName: String, vararg expectedRules: String) {
        val violation = assertThrows(AssertionError::class.java) {
            SourcePolicy.assertCompliant(listOf(fixtureRoot))
        }
        val violations = violation.message.orEmpty().substringAfter("Violations:\n")
        assertTrue(violations.contains(expectedFileName))
        expectedRules.forEach { expectedRule ->
            assertTrue(violations.contains(expectedRule))
        }
    }

    private object SourcePolicy {
        private const val supportedInventory =
            "System.currentTimeMillis/nanoTime; java.time Instant/LocalDateTime/OffsetDateTime/ZonedDateTime.now; " +
                "Clock.system*, UUID.randomUUID, kotlin.random.Random.Default, java.util.Random, SecureRandom, " +
                "ThreadLocalRandom.current, Dispatchers.Default/IO/Main/Unconfined and Java getters, GlobalScope"

        fun assertCompliant(sourceRoots: List<Path>) {
            val violations = sourceRoots.flatMap { sourceRoot ->
                Files.walk(sourceRoot).use { paths ->
                    paths.filter { Files.isRegularFile(it) && it.extension in sourceExtensions }
                        .flatMap { sourceFile -> violationsIn(sourceFile).stream() }
                        .collect(Collectors.toList())
                }
            }
            assertTrue(violations.isEmpty()) {
                "Domain/Runtime production sources must use injected clock, ID, and dispatcher ports. " +
                    "Supported direct-source inventory: $supportedInventory. Violations:\n" +
                    violations.joinToString("\n")
            }
        }

        private fun violationsIn(sourceFile: Path): List<String> {
            val lexicalSource = SourceLexer.lex(Files.readString(sourceFile), sourceFile.extension == "kt")
            val imports = importDeclaration.findAll(lexicalSource).map { match ->
                ImportedName(
                    target = match.groupValues[1].replace(Regex("\\s"), ""),
                    alias = match.groupValues[2].ifBlank { null },
                )
            }.toList()
            val executableSource = lexicalSource.lineSequence()
                .filterNot(importDeclaration::matches)
                .joinToString("\n")

            return forbiddenRules.filter { forbidden ->
                forbidden.matches(executableSource, imports)
            }.map { forbidden -> "$sourceFile: ${forbidden.description}" }
        }

        private data class ImportedName(val target: String, val alias: String?) {
            val localName: String get() = alias ?: target.substringAfterLast('.')
        }

        private enum class Usage { MEMBER_CALL, MEMBER_PROPERTY, CONSTRUCTOR, TYPE_REFERENCE }

        private data class ForbiddenRule(
            val description: String,
            val owner: String,
            val members: Set<String> = emptySet(),
            val usage: Usage,
        ) {
            fun matches(source: String, imports: List<ImportedName>): Boolean = when (usage) {
                Usage.MEMBER_CALL -> members.any { member ->
                    memberMatches(source, imports, member, "\\s*\\(")
                }

                Usage.MEMBER_PROPERTY -> members.any { member ->
                    memberMatches(source, imports, member, "\\b")
                }

                Usage.CONSTRUCTOR -> constructorMatches(source, imports)
                Usage.TYPE_REFERENCE -> typeNames(imports).any { name ->
                    Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(source)
                } || Regex("\\b${dotted(owner)}\\b").containsMatchIn(source)
            }

            private fun memberMatches(
                source: String,
                imports: List<ImportedName>,
                member: String,
                suffix: String,
            ): Boolean {
                val qualified = Regex("\\b${dotted(owner)}\\s*\\.\\s*${Regex.escape(member)}$suffix")
                if (qualified.containsMatchIn(source)) return true
                if (typeNames(imports).any { name ->
                        Regex("\\b${Regex.escape(name)}\\s*\\.\\s*${Regex.escape(member)}$suffix")
                            .containsMatchIn(source)
                    }) {
                    return true
                }
                return directMemberNames(imports, member).any { name ->
                    Regex("\\b${Regex.escape(name)}$suffix").containsMatchIn(source)
                }
            }

            private fun constructorMatches(source: String, imports: List<ImportedName>): Boolean =
                typeNames(imports).any { name -> callableNameMatches(source, name) } ||
                    callableNameMatches(source, dotted(owner), isPattern = true)

            private fun callableNameMatches(source: String, name: String, isPattern: Boolean = false): Boolean {
                val namePattern = if (isPattern) name else Regex.escape(name)
                return Regex("\\b(?:new\\s+)?$namePattern\\s*\\(").containsMatchIn(source)
            }

            private fun typeNames(imports: List<ImportedName>): Set<String> {
                val packageName = owner.substringBeforeLast('.')
                val simpleName = owner.substringAfterLast('.')
                return buildSet {
                    if (owner in implicitlyAvailableTypes) add(simpleName)
                    imports.filter { imported ->
                        imported.target == owner || imported.target == "$packageName.*"
                    }.forEach { imported -> add(imported.alias ?: simpleName) }
                }
            }

            private fun directMemberNames(imports: List<ImportedName>, member: String): Set<String> =
                imports.mapNotNullTo(mutableSetOf()) { imported ->
                    when (imported.target) {
                        "$owner.$member" -> imported.alias ?: member
                        "$owner.*" -> member
                        else -> null
                    }
                }
        }

        private val sourceExtensions = setOf("kt", "java")
        private val importDeclaration = Regex(
            "^\\s*import\\s+(?:static\\s+)?([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*(?:[A-Za-z_$][\\w$]*|\\*))*)(?:\\s+as\\s+([A-Za-z_]\\w*))?\\s*;?\\s*$",
            RegexOption.MULTILINE,
        )
        private val implicitlyAvailableTypes = setOf("java.lang.System", "kotlin.random.Random")
        private val forbiddenRules = listOf(
            ForbiddenRule("direct java.lang.System.currentTimeMillis()", "java.lang.System", setOf("currentTimeMillis"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.lang.System.nanoTime()", "java.lang.System", setOf("nanoTime"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.Instant.now()", "java.time.Instant", setOf("now"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.LocalDateTime.now()", "java.time.LocalDateTime", setOf("now"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.OffsetDateTime.now()", "java.time.OffsetDateTime", setOf("now"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.ZonedDateTime.now()", "java.time.ZonedDateTime", setOf("now"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.Clock.system*()", "java.time.Clock", setOf("system", "systemUTC", "systemDefaultZone"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.util.UUID.randomUUID()", "java.util.UUID", setOf("randomUUID"), Usage.MEMBER_CALL),
            ForbiddenRule("direct kotlin.random.Random.Default", "kotlin.random.Random", setOf("Default"), Usage.MEMBER_PROPERTY),
            ForbiddenRule("direct java.util.Random constructor", "java.util.Random", usage = Usage.CONSTRUCTOR),
            ForbiddenRule("direct java.security.SecureRandom constructor", "java.security.SecureRandom", usage = Usage.CONSTRUCTOR),
            ForbiddenRule("direct java.util.concurrent.ThreadLocalRandom.current()", "java.util.concurrent.ThreadLocalRandom", setOf("current"), Usage.MEMBER_CALL),
            ForbiddenRule("direct kotlinx.coroutines.Dispatchers property", "kotlinx.coroutines.Dispatchers", setOf("Default", "IO", "Main", "Unconfined"), Usage.MEMBER_PROPERTY),
            ForbiddenRule("direct kotlinx.coroutines.Dispatchers getter", "kotlinx.coroutines.Dispatchers", setOf("getDefault", "getIO", "getMain", "getUnconfined"), Usage.MEMBER_CALL),
            ForbiddenRule("direct kotlinx.coroutines.GlobalScope", "kotlinx.coroutines.GlobalScope", usage = Usage.TYPE_REFERENCE),
        )

        private fun dotted(name: String): String = name.split('.').joinToString("\\s*\\.\\s*") { Regex.escape(it) }

        private object SourceLexer {
            fun lex(source: String, isKotlin: Boolean): String = Scanner(source, isKotlin).scan()

            private class Scanner(private val source: String, private val isKotlin: Boolean) {
                private val output = StringBuilder()

                fun scan(): String {
                    scanCode(0, null)
                    return output.toString()
                }

                private fun scanCode(start: Int, templateDepth: Int?): Int {
                    var index = start
                    var braces = templateDepth
                    while (index < source.length) {
                        val current = source[index]
                        if (braces != null && current == '{') {
                            output.append(current)
                            braces += 1
                            index += 1
                            continue
                        }
                        if (braces != null && current == '}') {
                            mask(current)
                            braces -= 1
                            index += 1
                            if (braces == 0) return index
                            continue
                        }
                        when {
                            source.startsWith("//", index) -> index = skipLineComment(index)
                            source.startsWith("/*", index) -> index = skipBlockComment(index)
                            source.startsWith("\"\"\"", index) -> index = skipTripleQuoted(index)
                            current == '"' -> index = skipQuoted(index)
                            current == '\'' -> index = skipCharacter(index)
                            else -> {
                                output.append(current)
                                index += 1
                            }
                        }
                    }
                    return index
                }

                private fun skipLineComment(start: Int): Int {
                    var index = start
                    while (index < source.length && source[index] != '\n' && source[index] != '\r') {
                        mask(source[index])
                        index += 1
                    }
                    return index
                }

                private fun skipBlockComment(start: Int): Int {
                    var index = start
                    var depth = 1
                    maskRange(index, index + 2)
                    index += 2
                    while (index < source.length && depth > 0) {
                        when {
                            isKotlin && source.startsWith("/*", index) -> {
                                depth += 1
                                maskRange(index, index + 2)
                                index += 2
                            }

                            source.startsWith("*/", index) -> {
                                depth -= 1
                                maskRange(index, index + 2)
                                index += 2
                            }

                            else -> {
                                mask(source[index])
                                index += 1
                            }
                        }
                    }
                    return index
                }

                private fun skipTripleQuoted(start: Int): Int {
                    var index = start
                    maskRange(index, index + 3)
                    index += 3
                    while (index < source.length) {
                        when {
                            isKotlin && source.startsWith("${'$'}{", index) -> {
                                maskRange(index, index + 2)
                                index = scanCode(index + 2, 1)
                            }

                            source.startsWith("\"\"\"", index) -> {
                                maskRange(index, index + 3)
                                return index + 3
                            }

                            else -> {
                                mask(source[index])
                                index += 1
                            }
                        }
                    }
                    return index
                }

                private fun skipQuoted(start: Int): Int {
                    var index = start
                    mask(source[index])
                    index += 1
                    while (index < source.length) {
                        when {
                            isKotlin && source.startsWith("${'$'}{", index) -> {
                                maskRange(index, index + 2)
                                index = scanCode(index + 2, 1)
                            }

                            source[index] == '\\' -> {
                                mask(source[index])
                                index += 1
                                if (index < source.length) {
                                    mask(source[index])
                                    index += 1
                                }
                            }

                            source[index] == '"' -> {
                                mask(source[index])
                                return index + 1
                            }

                            else -> {
                                mask(source[index])
                                index += 1
                            }
                        }
                    }
                    return index
                }

                private fun skipCharacter(start: Int): Int {
                    var index = start
                    mask(source[index])
                    index += 1
                    while (index < source.length) {
                        if (source[index] == '\\') {
                            mask(source[index])
                            index += 1
                            if (index < source.length) {
                                mask(source[index])
                                index += 1
                            }
                        } else {
                            val current = source[index]
                            mask(current)
                            index += 1
                            if (current == '\'') return index
                        }
                    }
                    return index
                }

                private fun maskRange(start: Int, endExclusive: Int) {
                    (start until endExclusive).forEach { index -> mask(source[index]) }
                }

                private fun mask(character: Char) {
                    output.append(if (character == '\n' || character == '\r') character else ' ')
                }
            }
        }
    }
}
