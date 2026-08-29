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
                import java.time.Clock
                import java.time.Duration
                import java.util.Date
                import java.util.GregorianCalendar
                // System.currentTimeMillis() belongs in an adapter, not here.
                fun currentTimeMillisFromClock(clock: AppClock) = clock.now()
                fun tickInjectedClock(clock: Clock) = Clock.tick(clock, Duration.ofSeconds(1))
                fun sampleInjectedEntropy(injectedEntropy: EntropyPort) = injectedEntropy.nextInt()
                fun dateAt(injectedEpochMillis: Long) = Date(injectedEpochMillis)
                fun calendarFor(injectedZone: java.util.TimeZone) = GregorianCalendar(injectedZone)
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
            import java.util.UUID.randomUUID as freshId
            import kotlin.random.Random as SourceRandom
            import kotlinx.coroutines.Dispatchers as RuntimeDispatchers
            val id = freshId()
            val entropy = SourceRandom.Default.nextInt()
            val dispatcher = RuntimeDispatchers.IO
            """.trimIndent(),
        )

        assertPolicyRejects(
            fixtureRoot,
            "KotlinAliases.kt",
            "UUID.randomUUID",
            "kotlin.random.Random",
            "Dispatchers.IO",
        )
    }

    @Test
    fun sourcePolicyRejectsAliasedClockSystemDefaultZone() = assertRejectedFixture(
        "domain/AliasedClock.kt",
        """
        package dev.cockpit.domain
        import java.time.Clock as WallClock
        val clock = WallClock.systemDefaultZone()
        """.trimIndent(),
        "Clock.systemDefaultZone",
    )

    @Test
    fun sourcePolicyRejectsMemberImportedClockSystemUtc() = assertRejectedFixture(
        "domain/MemberImportedClock.kt",
        """
        package dev.cockpit.domain
        import java.time.Clock.systemUTC
        val clock = systemUTC()
        """.trimIndent(),
        "Clock.systemUTC",
    )

    @Test
    fun sourcePolicyRejectsKotlinRandomDirectImportedAliasMemberAndWildcardForms() {
        assertRejectedFixture(
            "domain/DirectKotlinRandom.kt",
            """
            package dev.cockpit.domain
            val boolean = kotlin.random.Random.nextBoolean()
            val bytes = kotlin.random.Random.nextBytes(4)
            val double = kotlin.random.Random.nextDouble()
            val float = kotlin.random.Random.nextFloat()
            val int = kotlin.random.Random.nextInt()
            val long = kotlin.random.Random.nextLong()
            val seeded = kotlin.random.Random(7)
            val default = kotlin.random.Random.Default
            """.trimIndent(),
            "Random.nextBoolean",
            "Random.nextBytes",
            "Random.nextDouble",
            "Random.nextFloat",
            "Random.nextInt",
            "Random.nextLong",
            "Random factory",
            "Random.Default",
        )
        assertRejectedFixture(
            "domain/ImportedKotlinRandom.kt",
            """
            package dev.cockpit.domain
            import kotlin.random.Random
            val random = Random.nextInt()
            """.trimIndent(),
            "Random.nextInt",
        )
        assertRejectedFixture(
            "domain/AliasedKotlinRandom.kt",
            """
            package dev.cockpit.domain
            import kotlin.random.Random as Entropy
            val random = Entropy.nextLong()
            """.trimIndent(),
            "Random.nextLong",
        )
        assertRejectedFixture(
            "domain/MemberImportedKotlinRandom.kt",
            """
            package dev.cockpit.domain
            import kotlin.random.Random.nextDouble
            val random = nextDouble()
            """.trimIndent(),
            "Random.nextDouble",
        )
        assertRejectedFixture(
            "domain/WildcardKotlinRandom.kt",
            """
            package dev.cockpit.domain
            import kotlin.random.*
            val random = Random.nextFloat()
            """.trimIndent(),
            "Random.nextFloat",
        )
    }

    @Test
    fun sourcePolicyRejectsMathAndStrictMathRandomCalls() {
        assertRejectedFixture(
            "runtime/MathRandom.java",
            """
            package dev.cockpit.runtime;
            class MathRandom { double random = Math.random(); }
            """.trimIndent(),
            "Math.random",
        )
        assertRejectedFixture(
            "runtime/StrictMathRandom.java",
            """
            package dev.cockpit.runtime;
            import static java.lang.StrictMath.random;
            class StrictMathRandom { double randomValue = random(); }
            """.trimIndent(),
            "StrictMath.random",
        )
    }

    @Test
    fun sourcePolicyRejectsEachRemainingJavaTimeNowFamily() {
        listOf(
            "LocalDate" to "java.time.LocalDate.now()",
            "LocalTime" to "java.time.LocalTime.now()",
            "OffsetTime" to "java.time.OffsetTime.now()",
            "Year" to "java.time.Year.now()",
            "YearMonth" to "java.time.YearMonth.now()",
            "MonthDay" to "java.time.MonthDay.now()",
        ).forEach { (type, expression) ->
            assertRejectedFixture(
                "runtime/${type}Now.java",
                """
                package dev.cockpit.runtime;
                class ${type}Now { Object value = $expression; }
                """.trimIndent(),
                "$type.now",
            )
        }
    }

    @Test
    fun sourcePolicyRejectsSystemBackedClockTickAcrossImportForms() {
        assertRejectedFixture(
            "runtime/QualifiedClockTick.java",
            """
            package dev.cockpit.runtime;
            class QualifiedClockTick {
                Object tick = java.time.Clock.tick(java.time.Clock.systemUTC(), java.time.Duration.ofSeconds(1));
            }
            """.trimIndent(),
            "Clock.tick(system-backed Clock)",
        )
        assertRejectedFixture(
            "runtime/ImportedClockTick.java",
            """
            package dev.cockpit.runtime;
            import java.time.Clock;
            class ImportedClockTick {
                Object tick = Clock.tick(Clock.systemDefaultZone(), java.time.Duration.ofSeconds(1));
            }
            """.trimIndent(),
            "Clock.tick(system-backed Clock)",
        )
        assertRejectedFixture(
            "domain/AliasedClockTick.kt",
            """
            package dev.cockpit.domain
            import java.time.Clock as RuntimeClock
            val tick = RuntimeClock.tick(RuntimeClock.systemUTC(), java.time.Duration.ofSeconds(1))
            """.trimIndent(),
            "Clock.tick(system-backed Clock)",
        )
        assertRejectedFixture(
            "domain/MemberImportedClockTick.kt",
            """
            package dev.cockpit.domain
            import java.time.Clock.tick
            import java.time.Clock.systemUTC
            val clock = tick(systemUTC(), java.time.Duration.ofSeconds(1))
            """.trimIndent(),
            "Clock.tick(system-backed Clock)",
        )
        assertRejectedFixture(
            "runtime/StaticClockTick.java",
            """
            package dev.cockpit.runtime;
            import static java.time.Clock.tick;
            import static java.time.Clock.systemUTC;
            class StaticClockTick {
                Object value = tick(systemUTC(), java.time.Duration.ofSeconds(1));
            }
            """.trimIndent(),
            "Clock.tick(system-backed Clock)",
        )
        assertRejectedFixture(
            "runtime/WildcardClockTick.java",
            """
            package dev.cockpit.runtime;
            import java.time.*;
            class WildcardClockTick {
                Object value = Clock.tick(Clock.systemUTC(), Duration.ofSeconds(1));
            }
            """.trimIndent(),
            "Clock.tick(system-backed Clock)",
        )
    }

    @Test
    fun sourcePolicyRejectsSecureRandomFactoriesAcrossImportForms() {
        assertRejectedFixture(
            "runtime/QualifiedSecureRandom.java",
            """
            package dev.cockpit.runtime;
            class QualifiedSecureRandom {
                Object instance = java.security.SecureRandom.getInstance("SHA1PRNG");
                Object strong = java.security.SecureRandom.getInstanceStrong();
                byte[] seed = java.security.SecureRandom.getSeed(8);
            }
            """.trimIndent(),
            "SecureRandom.getInstance",
            "SecureRandom.getInstanceStrong",
            "SecureRandom.getSeed",
        )
        assertRejectedFixture(
            "runtime/ImportedSecureRandom.java",
            """
            package dev.cockpit.runtime;
            import java.security.SecureRandom;
            class ImportedSecureRandom { Object instance = SecureRandom.getInstanceStrong(); }
            """.trimIndent(),
            "SecureRandom.getInstanceStrong",
        )
        assertRejectedFixture(
            "domain/AliasedSecureRandom.kt",
            """
            package dev.cockpit.domain
            import java.security.SecureRandom as SecureEntropy
            val seed = SecureEntropy.getSeed(8)
            """.trimIndent(),
            "SecureRandom.getSeed",
        )
        assertRejectedFixture(
            "domain/MemberImportedSecureRandom.kt",
            """
            package dev.cockpit.domain
            import java.security.SecureRandom.getInstance
            val instance = getInstance("SHA1PRNG")
            """.trimIndent(),
            "SecureRandom.getInstance",
        )
        assertRejectedFixture(
            "runtime/StaticWildcardSecureRandom.java",
            """
            package dev.cockpit.runtime;
            import static java.security.SecureRandom.*;
            class StaticWildcardSecureRandom { Object instance = getInstanceStrong(); }
            """.trimIndent(),
            "SecureRandom.getInstanceStrong",
        )
    }

    @Test
    fun sourcePolicyRejectsJava17RandomFactories() = assertRejectedFixture(
        "runtime/Java17RandomFactories.java",
        """
        package dev.cockpit.runtime;
        class Java17RandomFactories {
            Object split = new java.util.SplittableRandom();
            Object generator = java.util.random.RandomGenerator.getDefault();
            Object namedGenerator = java.util.random.RandomGenerator.of("L64X128MixRandom");
            Object factory = java.util.random.RandomGeneratorFactory.of("L64X128MixRandom");
        }
        """.trimIndent(),
        "SplittableRandom constructor",
        "RandomGenerator.getDefault",
        "RandomGenerator.of",
        "RandomGeneratorFactory.of",
    )

    @Test
    fun sourcePolicyRejectsEachRandomAndSecureRandomMemberIndependently() {
        listOf(
            "nextBoolean" to "kotlin.random.Random.nextBoolean()",
            "nextBytes" to "kotlin.random.Random.nextBytes(4)",
            "nextDouble" to "kotlin.random.Random.nextDouble()",
            "nextFloat" to "kotlin.random.Random.nextFloat()",
            "nextInt" to "kotlin.random.Random.nextInt()",
            "nextLong" to "kotlin.random.Random.nextLong()",
        ).forEach { (member, call) ->
            assertRejectedFixture(
                "domain/Random${member.replaceFirstChar(Char::uppercase)}.kt",
                """
                package dev.cockpit.domain
                val value = $call
                """.trimIndent(),
                "Random.$member",
            )
        }
        listOf(
            "getInstance" to "java.security.SecureRandom.getInstance(\"SHA1PRNG\")",
            "getInstanceStrong" to "java.security.SecureRandom.getInstanceStrong()",
            "getSeed" to "java.security.SecureRandom.getSeed(8)",
        ).forEach { (member, call) ->
            assertRejectedFixture(
                "runtime/SecureRandom${member.replaceFirstChar(Char::uppercase)}.java",
                """
                package dev.cockpit.runtime;
                class SecureRandom$member { Object value = $call; }
                """.trimIndent(),
                "SecureRandom.$member",
            )
        }
    }

    @Test
    fun sourcePolicyRejectsEachRandomGeneratorFactoryMemberIndependently() {
        listOf(
            "RandomGeneratorGetDefault" to "java.util.random.RandomGenerator.getDefault()" to "RandomGenerator.getDefault",
            "RandomGeneratorOf" to "java.util.random.RandomGenerator.of(\"L64X128MixRandom\")" to "RandomGenerator.of",
            "RandomGeneratorFactoryGetDefault" to "java.util.random.RandomGeneratorFactory.getDefault()" to "RandomGeneratorFactory.getDefault",
            "RandomGeneratorFactoryOf" to "java.util.random.RandomGeneratorFactory.of(\"L64X128MixRandom\")" to "RandomGeneratorFactory.of",
        ).forEach { (fixture, rule) ->
            val (name, call) = fixture
            assertRejectedFixture(
                "runtime/$name.java",
                """
                package dev.cockpit.runtime;
                class $name { Object value = $call; }
                """.trimIndent(),
                rule,
            )
        }
    }

    @Test
    fun sourcePolicyRejectsEachClockSystemAndDispatcherMemberIndependently() {
        listOf(
            "system" to "java.time.Clock.system(java.time.ZoneOffset.UTC)",
            "systemUTC" to "java.time.Clock.systemUTC()",
            "systemDefaultZone" to "java.time.Clock.systemDefaultZone()",
        ).forEach { (member, call) ->
            assertRejectedFixture(
                "runtime/Clock${member.replaceFirstChar(Char::uppercase)}.java",
                """
                package dev.cockpit.runtime;
                class Clock$member { Object value = $call; }
                """.trimIndent(),
                "Clock.$member",
            )
        }
        listOf("Default", "IO", "Main", "Unconfined").forEach { lane ->
            assertRejectedFixture(
                "runtime/Dispatcher$lane.java",
                """
                package dev.cockpit.runtime;
                class Dispatcher$lane { Object value = kotlinx.coroutines.Dispatchers.$lane; }
                """.trimIndent(),
                "Dispatchers.$lane",
            )
        }
        listOf("getDefault", "getIO", "getMain", "getUnconfined").forEach { getter ->
            assertRejectedFixture(
                "runtime/Dispatcher${getter.replaceFirstChar(Char::uppercase)}.java",
                """
                package dev.cockpit.runtime;
                class Dispatcher$getter { Object value = kotlinx.coroutines.Dispatchers.$getter(); }
                """.trimIndent(),
                "Dispatchers.$getter",
            )
        }
    }

    @Test
    fun sourcePolicyRejectsLegacyNoArgCalendarSourcesAcrossImportForms() {
        assertRejectedFixture(
            "runtime/NewDate.java",
            """
            package dev.cockpit.runtime;
            class NewDate { Object value = new java.util.Date(); }
            """.trimIndent(),
            "java.util.Date no-arg constructor",
        )
        assertRejectedFixture(
            "domain/KotlinDate.kt",
            """
            package dev.cockpit.domain
            import java.util.Date
            val value = Date()
            """.trimIndent(),
            "java.util.Date no-arg constructor",
        )
        assertRejectedFixture(
            "runtime/CalendarDirect.java",
            """
            package dev.cockpit.runtime;
            class CalendarDirect { Object value = java.util.Calendar.getInstance(); }
            """.trimIndent(),
            "Calendar.getInstance",
        )
        assertRejectedFixture(
            "runtime/CalendarImported.java",
            """
            package dev.cockpit.runtime;
            import java.util.Calendar;
            class CalendarImported { Object value = Calendar.getInstance(); }
            """.trimIndent(),
            "Calendar.getInstance",
        )
        assertRejectedFixture(
            "runtime/CalendarStatic.java",
            """
            package dev.cockpit.runtime;
            import static java.util.Calendar.getInstance;
            class CalendarStatic { Object value = getInstance(); }
            """.trimIndent(),
            "Calendar.getInstance",
        )
        assertRejectedFixture(
            "runtime/NewGregorianCalendar.java",
            """
            package dev.cockpit.runtime;
            class NewGregorianCalendar { Object value = new java.util.GregorianCalendar(); }
            """.trimIndent(),
            "GregorianCalendar no-arg constructor",
        )
    }

    @Test
    fun sourcePolicyRejectsKotlinClockSystemNowAcrossImportForms() {
        assertRejectedFixture(
            "domain/QualifiedKotlinClock.kt",
            """
            package dev.cockpit.domain
            val value = kotlin.time.Clock.System.now()
            """.trimIndent(),
            "kotlin.time.Clock.System.now",
        )
        assertRejectedFixture(
            "domain/ImportedKotlinClock.kt",
            """
            package dev.cockpit.domain
            import kotlin.time.Clock
            val value = Clock.System.now()
            """.trimIndent(),
            "kotlin.time.Clock.System.now",
        )
        assertRejectedFixture(
            "domain/ImportedKotlinClockSystem.kt",
            """
            package dev.cockpit.domain
            import kotlin.time.Clock.System
            val value = System.now()
            """.trimIndent(),
            "kotlin.time.Clock.System.now",
        )
        assertRejectedFixture(
            "domain/AliasedKotlinClockSystem.kt",
            """
            package dev.cockpit.domain
            import kotlin.time.Clock.System as PlatformClock
            val value = PlatformClock.now()
            """.trimIndent(),
            "kotlin.time.Clock.System.now",
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
            "Clock.systemUTC",
            "UUID.randomUUID",
            "java.util.Random",
            "SecureRandom",
            "ThreadLocalRandom",
            "Dispatchers.getDefault",
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
            "Clock.systemUTC",
            "UUID.randomUUID",
            "java.util.Random",
            "SecureRandom",
            "ThreadLocalRandom",
            "Dispatchers.getIO",
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

    private fun assertRejectedFixture(relativePath: String, source: String, vararg expectedRules: String) {
        withFixtureRoot { fixtureRoot ->
            writeFixture(fixtureRoot, relativePath, source)
            assertPolicyRejects(fixtureRoot, relativePath.substringAfterLast('/'), *expectedRules)
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
                "LocalDate/LocalTime/OffsetTime/Year/YearMonth/MonthDay.now; Date()/Calendar.getInstance()/GregorianCalendar(); " +
                "Clock.system/systemUTC/systemDefaultZone and tick(system-backed Clock); kotlin.time.Clock.System.now(); " +
                "UUID.randomUUID, kotlin.random.Random.Default/nextBoolean/nextBytes/nextDouble/nextFloat/nextInt/nextLong/factory, " +
                "Math.random, StrictMath.random, java.util.Random, SecureRandom constructor/getInstance/getInstanceStrong/getSeed, " +
                "ThreadLocalRandom.current, SplittableRandom constructor, RandomGenerator/RandomGeneratorFactory getDefault/of, " +
                "Dispatchers.Default/IO/Main/Unconfined and getDefault/getIO/getMain/getUnconfined, GlobalScope"

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

        private enum class Usage {
            MEMBER_CALL,
            MEMBER_PROPERTY,
            CONSTRUCTOR,
            NO_ARGUMENT_CONSTRUCTOR,
            TYPE_REFERENCE,
            SYSTEM_BACKED_TICK,
            KOTLIN_CLOCK_SYSTEM_NOW,
        }

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
                Usage.NO_ARGUMENT_CONSTRUCTOR -> noArgumentConstructorMatches(source, imports)
                Usage.TYPE_REFERENCE -> typeNames(imports).any { name ->
                    Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(source)
                } || Regex("\\b${dotted(owner)}\\b").containsMatchIn(source)
                Usage.SYSTEM_BACKED_TICK -> systemBackedTickMatches(source, imports)
                Usage.KOTLIN_CLOCK_SYSTEM_NOW -> kotlinClockSystemNowMatches(source, imports)
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

            private fun noArgumentConstructorMatches(source: String, imports: List<ImportedName>): Boolean =
                typeNames(imports).any { name -> noArgumentCallableNameMatches(source, name) } ||
                    noArgumentCallableNameMatches(source, dotted(owner), isPattern = true)

            private fun systemBackedTickMatches(source: String, imports: List<ImportedName>): Boolean {
                val tickPrefixes = memberPrefixes(imports, "tick")
                val systemPrefixes = setOf("system", "systemUTC", "systemDefaultZone")
                    .flatMap { memberPrefixes(imports, it) }
                return tickPrefixes.any { tickPrefix ->
                    systemPrefixes.any { systemPrefix ->
                        Regex("\\b$tickPrefix\\s*\\(\\s*$systemPrefix\\s*\\(").containsMatchIn(source)
                    }
                }
            }

            private fun kotlinClockSystemNowMatches(source: String, imports: List<ImportedName>): Boolean {
                val qualified = Regex("\\b${dotted(owner)}\\s*\\.\\s*System\\s*\\.\\s*now\\s*\\(")
                if (qualified.containsMatchIn(source)) return true
                if (typeNames(imports).any { name ->
                        Regex("\\b${Regex.escape(name)}\\s*\\.\\s*System\\s*\\.\\s*now\\s*\\(")
                            .containsMatchIn(source)
                    }) {
                    return true
                }
                return imports.filter { imported -> imported.target == "$owner.System" }
                    .map { imported -> imported.localName }
                    .any { name -> Regex("\\b${Regex.escape(name)}\\s*\\.\\s*now\\s*\\(").containsMatchIn(source) }
            }

            private fun memberPrefixes(imports: List<ImportedName>, member: String): Set<String> = buildSet {
                add("${dotted(owner)}\\s*\\.\\s*${Regex.escape(member)}")
                typeNames(imports).forEach { name ->
                    add("${Regex.escape(name)}\\s*\\.\\s*${Regex.escape(member)}")
                }
                directMemberNames(imports, member).forEach { name -> add(Regex.escape(name)) }
            }

            private fun callableNameMatches(source: String, name: String, isPattern: Boolean = false): Boolean {
                val namePattern = if (isPattern) name else Regex.escape(name)
                return Regex("\\b(?:new\\s+)?$namePattern\\s*\\(").containsMatchIn(source)
            }

            private fun noArgumentCallableNameMatches(source: String, name: String, isPattern: Boolean = false): Boolean {
                val namePattern = if (isPattern) name else Regex.escape(name)
                return Regex("\\b(?:new\\s+)?$namePattern\\s*\\(\\s*\\)").containsMatchIn(source)
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
        private val implicitlyAvailableTypes = setOf(
            "java.lang.System",
            "java.lang.Math",
            "java.lang.StrictMath",
            "kotlin.random.Random",
        )
        private val forbiddenRules = listOf(
            ForbiddenRule("direct java.lang.System.currentTimeMillis()", "java.lang.System", setOf("currentTimeMillis"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.lang.System.nanoTime()", "java.lang.System", setOf("nanoTime"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.Instant.now()", "java.time.Instant", setOf("now"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.LocalDateTime.now()", "java.time.LocalDateTime", setOf("now"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.OffsetDateTime.now()", "java.time.OffsetDateTime", setOf("now"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.ZonedDateTime.now()", "java.time.ZonedDateTime", setOf("now"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.LocalDate.now()", "java.time.LocalDate", setOf("now"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.LocalTime.now()", "java.time.LocalTime", setOf("now"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.OffsetTime.now()", "java.time.OffsetTime", setOf("now"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.Year.now()", "java.time.Year", setOf("now"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.YearMonth.now()", "java.time.YearMonth", setOf("now"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.MonthDay.now()", "java.time.MonthDay", setOf("now"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.Clock.system()", "java.time.Clock", setOf("system"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.Clock.systemUTC()", "java.time.Clock", setOf("systemUTC"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.Clock.systemDefaultZone()", "java.time.Clock", setOf("systemDefaultZone"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.time.Clock.tick(system-backed Clock)", "java.time.Clock", usage = Usage.SYSTEM_BACKED_TICK),
            ForbiddenRule("direct java.util.Date no-arg constructor", "java.util.Date", usage = Usage.NO_ARGUMENT_CONSTRUCTOR),
            ForbiddenRule("direct java.util.Calendar.getInstance()", "java.util.Calendar", setOf("getInstance"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.util.GregorianCalendar no-arg constructor", "java.util.GregorianCalendar", usage = Usage.NO_ARGUMENT_CONSTRUCTOR),
            ForbiddenRule("direct kotlin.time.Clock.System.now()", "kotlin.time.Clock", usage = Usage.KOTLIN_CLOCK_SYSTEM_NOW),
            ForbiddenRule("direct java.util.UUID.randomUUID()", "java.util.UUID", setOf("randomUUID"), Usage.MEMBER_CALL),
            ForbiddenRule("direct kotlin.random.Random.Default", "kotlin.random.Random", setOf("Default"), Usage.MEMBER_PROPERTY),
            ForbiddenRule("direct kotlin.random.Random.nextBoolean()", "kotlin.random.Random", setOf("nextBoolean"), Usage.MEMBER_CALL),
            ForbiddenRule("direct kotlin.random.Random.nextBytes()", "kotlin.random.Random", setOf("nextBytes"), Usage.MEMBER_CALL),
            ForbiddenRule("direct kotlin.random.Random.nextDouble()", "kotlin.random.Random", setOf("nextDouble"), Usage.MEMBER_CALL),
            ForbiddenRule("direct kotlin.random.Random.nextFloat()", "kotlin.random.Random", setOf("nextFloat"), Usage.MEMBER_CALL),
            ForbiddenRule("direct kotlin.random.Random.nextInt()", "kotlin.random.Random", setOf("nextInt"), Usage.MEMBER_CALL),
            ForbiddenRule("direct kotlin.random.Random.nextLong()", "kotlin.random.Random", setOf("nextLong"), Usage.MEMBER_CALL),
            ForbiddenRule("direct kotlin.random.Random factory", "kotlin.random.Random", usage = Usage.CONSTRUCTOR),
            ForbiddenRule("direct java.lang.Math.random()", "java.lang.Math", setOf("random"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.lang.StrictMath.random()", "java.lang.StrictMath", setOf("random"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.util.Random constructor", "java.util.Random", usage = Usage.CONSTRUCTOR),
            ForbiddenRule("direct java.security.SecureRandom constructor", "java.security.SecureRandom", usage = Usage.CONSTRUCTOR),
            ForbiddenRule("direct java.security.SecureRandom.getInstance()", "java.security.SecureRandom", setOf("getInstance"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.security.SecureRandom.getInstanceStrong()", "java.security.SecureRandom", setOf("getInstanceStrong"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.security.SecureRandom.getSeed()", "java.security.SecureRandom", setOf("getSeed"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.util.concurrent.ThreadLocalRandom.current()", "java.util.concurrent.ThreadLocalRandom", setOf("current"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.util.SplittableRandom constructor", "java.util.SplittableRandom", usage = Usage.CONSTRUCTOR),
            ForbiddenRule("direct java.util.random.RandomGenerator.getDefault()", "java.util.random.RandomGenerator", setOf("getDefault"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.util.random.RandomGenerator.of()", "java.util.random.RandomGenerator", setOf("of"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.util.random.RandomGeneratorFactory.getDefault()", "java.util.random.RandomGeneratorFactory", setOf("getDefault"), Usage.MEMBER_CALL),
            ForbiddenRule("direct java.util.random.RandomGeneratorFactory.of()", "java.util.random.RandomGeneratorFactory", setOf("of"), Usage.MEMBER_CALL),
            ForbiddenRule("direct kotlinx.coroutines.Dispatchers.Default", "kotlinx.coroutines.Dispatchers", setOf("Default"), Usage.MEMBER_PROPERTY),
            ForbiddenRule("direct kotlinx.coroutines.Dispatchers.IO", "kotlinx.coroutines.Dispatchers", setOf("IO"), Usage.MEMBER_PROPERTY),
            ForbiddenRule("direct kotlinx.coroutines.Dispatchers.Main", "kotlinx.coroutines.Dispatchers", setOf("Main"), Usage.MEMBER_PROPERTY),
            ForbiddenRule("direct kotlinx.coroutines.Dispatchers.Unconfined", "kotlinx.coroutines.Dispatchers", setOf("Unconfined"), Usage.MEMBER_PROPERTY),
            ForbiddenRule("direct kotlinx.coroutines.Dispatchers.getDefault()", "kotlinx.coroutines.Dispatchers", setOf("getDefault"), Usage.MEMBER_CALL),
            ForbiddenRule("direct kotlinx.coroutines.Dispatchers.getIO()", "kotlinx.coroutines.Dispatchers", setOf("getIO"), Usage.MEMBER_CALL),
            ForbiddenRule("direct kotlinx.coroutines.Dispatchers.getMain()", "kotlinx.coroutines.Dispatchers", setOf("getMain"), Usage.MEMBER_CALL),
            ForbiddenRule("direct kotlinx.coroutines.Dispatchers.getUnconfined()", "kotlinx.coroutines.Dispatchers", setOf("getUnconfined"), Usage.MEMBER_CALL),
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
