package dev.cockpit.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import kotlin.io.path.exists

class ModuleGraphTest {
    @Test
    fun presentationCannotReachAdapters() {
        assertFrozenGraph(GradleModuleGraph.read(projectRoot()))
    }

    private fun assertFrozenGraph(graph: GradleModuleGraph) {
        assertEquals(
            expectedProjects,
            graph.declaredProjects,
            "The declared project set must match the frozen Module and Dependency Map.",
        )
        assertEquals(
            expectedEdges,
            graph.allowedEdges,
            "The explicit allowed-edge manifest must contain every and only the frozen direct production edges.",
        )
        assertEquals(
            expectedEdges,
            graph.productionEdges,
            "Actual Gradle production project dependencies must match the frozen direct edge set.",
        )
        assertTrue(
            graph.productionEdges.all { it in graph.allowedEdges },
            "Every actual production project dependency must be declared in the allowed-edge manifest.",
        )
        assertTrue(
            graph.nonTestStructuralEdges.isEmpty(),
            "The root and structural container projects must not declare non-test project dependencies: ${graph.nonTestStructuralEdges}",
        )
        assertFalse(
            graph.productionEdges.any { (_, target) -> target.startsWith(":spikes:") },
            "Production modules must not depend on spike projects.",
        )
        assertEquals(
            setOf(Edge(":app", ":presentation"), Edge(":app", ":platform:android")),
            graph.productionEdges.filter { (source, _) -> source == ":app" }.toSet(),
            ":app must remain packaging-only.",
        )
        assertTrue(
            requiredAppProductionCompileClasspaths.all { it in graph.appProductionCompileVisibility },
            ":app must expose every required production compile classpath to architecture verification: " +
                "${graph.appProductionCompileVisibility.keys}",
        )
        graph.appProductionCompileVisibility.forEach { (configuration, visibleProjects) ->
            assertEquals(
                expectedAppCompileVisibility,
                visibleProjects,
                ":app $configuration must expose only the frozen packaging-boundary projects.",
            )
        }

        val reachableFromPresentation = graph.reachableFrom(":presentation")
        setOf(
            ":integration:ssh",
            ":integration:provider",
            ":data:persistence-room",
            ":platform:android",
        ).forEach { adapter ->
            assertFalse(
                adapter in reachableFromPresentation,
                ":presentation must not reach concrete adapter $adapter.",
            )
        }
    }

    @Test
    fun rejectsWhitespaceProjectInclusion() {
        val fixtureDirectory = Files.createTempDirectory("cockpit-module-graph-project-")
        try {
            assertGraphRejectsInitScript(
                """
                gradle.settingsEvaluated { settings ->
                    settings.include (":architecture-graph-whitespace-fixture")
                    settings.project(":architecture-graph-whitespace-fixture").projectDir =
                        new File(${groovyString(fixtureDirectory.toString())})
                }
                """.trimIndent(),
            )
        } finally {
            Files.deleteIfExists(fixtureDirectory)
        }
    }

    @Test
    fun rejectsProductionDependencyInjectedFromRootBuildLogic() {
        assertGraphRejectsInitScript(
            """
            gradle.projectsEvaluated {
                def source = gradle.rootProject.project(":presentation")
                source.dependencies.add(
                    "implementation",
                    source.dependencies.project(path: ":spikes:ssh-transport"),
                )
            }
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsNonTestProjectDependencyFromStructuralContainer() {
        assertGraphRejectsInitScript(
            """
            gradle.projectsEvaluated {
                def source = gradle.rootProject.project(":core")
                def architectureGraph = source.configurations.maybeCreate("architectureGraph")
                source.dependencies.add(
                    architectureGraph.name,
                    source.dependencies.project(path: ":spikes:ssh-transport"),
                )
            }
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsTestNamedConfigurationInheritedByProductionClasspath() {
        val graph = GradleModuleGraph.read(
            projectRoot(),
            """
            gradle.projectsEvaluated {
                def source = gradle.rootProject.project(":app")
                def hidden = source.configurations.maybeCreate("contestImplementation")
                source.configurations.getByName("implementation").extendsFrom(hidden)
                source.dependencies.add(
                    hidden.name,
                    source.dependencies.project(path: ":integration:ssh"),
                )
            }
            """.trimIndent(),
        )
        val hiddenAdapter = Edge(":app", ":integration:ssh")
        assertTrue(
            hiddenAdapter in graph.effectiveProductionClasspathEdges,
            "The evaluated authority must observe inherited project dependencies on the actual production classpath.",
        )
        assertGraphRejects(graph)
    }

    @Test
    fun rejectsAllowedAdapterPromotedOntoAppCompileClasspath() {
        val graph = GradleModuleGraph.read(
            projectRoot(),
            """
            gradle.projectsEvaluated {
                def platform = gradle.rootProject.project(":platform:android")
                platform.dependencies.add(
                    "api",
                    platform.dependencies.project(path: ":integration:ssh"),
                )
            }
            """.trimIndent(),
        )
        assertEquals(
            expectedEdges,
            graph.productionEdges,
            "The API-leak adversary must preserve the frozen declaration-edge set.",
        )
        requiredAppProductionCompileClasspaths.forEach { configuration ->
            assertTrue(
                ":integration:ssh" in graph.appProductionCompileVisibility.getValue(configuration),
                "The resolved $configuration must prove that the promoted adapter became compile-visible.",
            )
        }
        assertGraphRejects(graph)
    }

    @Test
    fun failsClosedWhenAppProductionCompileClasspathCannotResolve() {
        val failure = assertThrows(AssertionError::class.java) {
            GradleModuleGraph.read(
                projectRoot(),
                """
                gradle.projectsEvaluated {
                    def app = gradle.rootProject.project(":app")
                    app.configurations.matching {
                        it.name == "debugCompileClasspath"
                    }.configureEach { configuration ->
                        configuration.incoming.beforeResolve {
                            throw new GradleException("synthetic production compile resolution failure")
                        }
                    }
                }
                """.trimIndent(),
            )
        }
        assertTrue(
            failure.message.orEmpty().contains("synthetic production compile resolution failure"),
            "Resolution failure evidence must survive the fail-closed snapshot boundary: ${failure.message}",
        )
    }

    private fun projectRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { it.resolve(".git").exists() }

    private fun assertGraphRejectsInitScript(initScript: String) {
        val root = projectRoot()
        val trackedSettings = root.resolve("settings.gradle.kts")
        val trackedRootBuild = root.resolve("build.gradle.kts")
        val settingsBefore = Files.readAllBytes(trackedSettings)
        val rootBuildBefore = Files.readAllBytes(trackedRootBuild)
        val graph = GradleModuleGraph.read(root, initScript)
        assertTrue(
            settingsBefore.contentEquals(Files.readAllBytes(trackedSettings)),
            "Adversarial graph evaluation must not mutate tracked settings.gradle.kts.",
        )
        assertTrue(
            rootBuildBefore.contentEquals(Files.readAllBytes(trackedRootBuild)),
            "Adversarial graph evaluation must not mutate tracked build.gradle.kts.",
        )
        assertGraphRejects(graph)
    }

    private fun assertGraphRejects(graph: GradleModuleGraph) {
        try {
            assertFrozenGraph(graph)
        } catch (_: AssertionError) {
            return
        }
        fail<Nothing>("Expected graph rejection, but evaluated production edges were ${graph.productionEdges}.")
    }

    private fun groovyString(value: String): String =
        "'${value.replace("\\", "\\\\").replace("'", "\\'")}'"

    private data class Edge(val source: String, val target: String)

    private class GradleModuleGraph(
        val declaredProjects: Set<String>,
        val allowedEdges: Set<Edge>,
        val productionEdges: Set<Edge>,
        val effectiveProductionClasspathEdges: Set<Edge>,
        val nonTestStructuralEdges: Set<Edge>,
        val appProductionCompileVisibility: Map<String, Set<String>>,
    ) {
        fun reachableFrom(source: String): Set<String> {
            val visited = mutableSetOf<String>()
            val pending = ArrayDeque<String>()
            pending += source
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                productionEdges.filter { (edgeSource, _) -> edgeSource == current }.forEach { (_, target) ->
                    if (visited.add(target)) pending += target
                }
            }
            return visited
        }

        companion object {
            fun read(root: Path, injectedInitScript: String? = null): GradleModuleGraph {
                val evaluatedModel = readEvaluatedModel(root, injectedInitScript)
                val manifest = parseManifest(root.resolve("gradle/module-graph.txt"))
                assertTrue(
                    structuralProjectPaths.all { it in evaluatedModel.projects },
                    "Gradle must retain its deterministic root and structural container projects.",
                )
                val effectiveProductionClasspathEdges = evaluatedModel.effectiveProductionClasspathDependencies
                    .filter { it.source in productionProjects }
                    .map { Edge(it.source, it.target) }
                    .toSet()
                val directNonTestProductionEdges = evaluatedModel.dependencies
                    .filter { it.source in productionProjects && !isExplicitTestOnlyConfiguration(it.configuration) }
                    .map { Edge(it.source, it.target) }
                    .toSet()
                val productionEdges = directNonTestProductionEdges + effectiveProductionClasspathEdges
                val nonTestStructuralEdges = evaluatedModel.dependencies
                    .filter { it.source in structuralProjectPaths && !isExplicitTestOnlyConfiguration(it.configuration) }
                    .map { Edge(it.source, it.target) }
                    .toSet()
                return GradleModuleGraph(
                    evaluatedModel.projects - structuralProjectPaths,
                    manifest,
                    productionEdges,
                    effectiveProductionClasspathEdges,
                    nonTestStructuralEdges,
                    evaluatedModel.appProductionCompileVisibility,
                )
            }

            private fun readEvaluatedModel(root: Path, injectedInitScript: String?): EvaluatedModel {
                val temporaryDirectory = Files.createTempDirectory("cockpit-module-graph-")
                val initScript = temporaryDirectory.resolve("model.gradle")
                val mutationScript = injectedInitScript?.let { temporaryDirectory.resolve("mutation.gradle") }
                val command = if (System.getProperty("os.name").startsWith("Windows")) {
                    mutableListOf("cmd", "/c", "gradlew.bat")
                } else {
                    mutableListOf("./gradlew")
                }.apply {
                    addAll(listOf("--no-daemon", "--console=plain", "-I", initScript.toString()))
                    if (mutationScript != null) {
                        addAll(listOf("-I", mutationScript.toString()))
                    }
                    add("cockpitModuleGraphSnapshot")
                }
                val result = BoundedProcessRunner.run(
                    command = command,
                    workingDirectory = root,
                    timeout = 60,
                    unit = TimeUnit.SECONDS,
                    ownedTemporaryDirectory = temporaryDirectory,
                    prepare = {
                        Files.writeString(initScript, evaluatedModelInitScript)
                        if (mutationScript != null) {
                            Files.writeString(mutationScript, injectedInitScript)
                        }
                    },
                )
                val output = result.output
                assertTrue(result.completed, "Timed out while reading the evaluated Gradle module graph.\n$output")
                assertEquals(0, result.exitCode, "Unable to read the evaluated Gradle module graph:\n$output")
                return parseEvaluatedModel(output)
            }

            private fun parseManifest(file: Path): Set<Edge> {
                assertTrue(file.exists(), "The allowed-edge manifest is required.")
                return Files.readString(file).lineSequence().filter { it.isNotBlank() }.mapIndexed { index, rawLine ->
                    val match = manifestDeclaration.matchEntire(rawLine.trim())
                        ?: throw AssertionError("Unsupported allowed-edge manifest entry at line ${index + 1}: $rawLine")
                    Edge(match.groupValues[1], match.groupValues[2])
                }.toSet()
            }

            private fun parseEvaluatedModel(output: String): EvaluatedModel {
                val projects = mutableSetOf<String>()
                val dependencies = mutableSetOf<EvaluatedProjectDependency>()
                val effectiveProductionClasspathDependencies = mutableSetOf<EvaluatedProjectDependency>()
                val appProductionCompileVisibility = mutableMapOf<String, MutableSet<String>>()
                output.lineSequence().forEach { line ->
                    when {
                        line.startsWith(projectMarker) -> projects += line.removePrefix(projectMarker)
                        line.startsWith(dependencyMarker) -> {
                            val fields = line.removePrefix(dependencyMarker).split('|')
                            assertEquals(3, fields.size, "Malformed evaluated project dependency: $line")
                            dependencies += EvaluatedProjectDependency(fields[0], fields[1], fields[2])
                        }
                        line.startsWith(effectiveDependencyMarker) -> {
                            val fields = line.removePrefix(effectiveDependencyMarker).split('|')
                            assertEquals(3, fields.size, "Malformed effective production classpath dependency: $line")
                            effectiveProductionClasspathDependencies +=
                                EvaluatedProjectDependency(fields[0], fields[1], fields[2])
                        }
                        line.startsWith(appCompileClasspathMarker) -> {
                            val configuration = line.removePrefix(appCompileClasspathMarker)
                            assertTrue(configuration.isNotBlank(), "Malformed app production compile classpath: $line")
                            appProductionCompileVisibility.getOrPut(configuration) { mutableSetOf() }
                        }
                        line.startsWith(appCompileProjectMarker) -> {
                            val fields = line.removePrefix(appCompileProjectMarker).split('|')
                            assertEquals(2, fields.size, "Malformed app compile-visible project: $line")
                            appProductionCompileVisibility.getOrPut(fields[0]) { mutableSetOf() } += fields[1]
                        }
                    }
                }
                assertTrue(projects.isNotEmpty(), "The evaluated Gradle module graph produced no project paths.\n$output")
                return EvaluatedModel(
                    projects,
                    dependencies,
                    effectiveProductionClasspathDependencies,
                    appProductionCompileVisibility.mapValues { (_, projects) -> projects.toSet() },
                )
            }

            private fun isExplicitTestOnlyConfiguration(name: String): Boolean =
                explicitTestOnlyConfiguration.matches(name)

            private val manifestDeclaration = Regex("^(:[A-Za-z0-9-]+(?::[A-Za-z0-9-]+)*) -> (:[A-Za-z0-9-]+(?::[A-Za-z0-9-]+)*)$")
            private const val projectMarker = "COCKPIT_MODULE_PROJECT="
            private const val dependencyMarker = "COCKPIT_MODULE_EDGE="
            private const val effectiveDependencyMarker = "COCKPIT_MODULE_EFFECTIVE_PRODUCTION_EDGE="
            private const val appCompileClasspathMarker = "COCKPIT_APP_PRODUCTION_COMPILE_CLASSPATH="
            private const val appCompileProjectMarker = "COCKPIT_APP_COMPILE_PROJECT="
            private val explicitTestOnlyConfiguration = Regex(
                """(?:(?:test|androidTest|testFixtures)|(?:[a-z][A-Za-z0-9]*)?(?:UnitTest|AndroidTest))(?:Api|Implementation|CompileOnly|RuntimeOnly|CompileClasspath|RuntimeClasspath|AnnotationProcessor|Kapt|Ksp)""",
            )
            private val evaluatedModelInitScript =
                """
                gradle.projectsEvaluated {
                    gradle.rootProject.tasks.register("cockpitModuleGraphSnapshot") {
                        doLast {
                            def root = project.rootProject
                            root.allprojects.collect { it.path }.sort().each {
                                println("COCKPIT_MODULE_PROJECT=" + it)
                            }
                            def explicitTestOnlyConfiguration = { name ->
                                name ==~ /(?:(?:test|androidTest|testFixtures)|(?:[a-z][A-Za-z0-9]*)?(?:UnitTest|AndroidTest))(?:Api|Implementation|CompileOnly|RuntimeOnly|CompileClasspath|RuntimeClasspath|AnnotationProcessor|Kapt|Ksp)/
                            }
                            root.allprojects.each { source ->
                                source.configurations.each { configuration ->
                                    configuration.dependencies.withType(org.gradle.api.artifacts.ProjectDependency).each { dependency ->
                                        println("COCKPIT_MODULE_EDGE=" + source.path + "|" + configuration.name + "|" + dependency.path)
                                    }
                                    def productionClasspath =
                                        (configuration.name == "compileClasspath" ||
                                            configuration.name == "runtimeClasspath" ||
                                            configuration.name.endsWith("CompileClasspath") ||
                                            configuration.name.endsWith("RuntimeClasspath")) &&
                                        !explicitTestOnlyConfiguration(configuration.name)
                                    if (productionClasspath) {
                                        configuration.allDependencies.withType(org.gradle.api.artifacts.ProjectDependency).each { dependency ->
                                            println("COCKPIT_MODULE_EFFECTIVE_PRODUCTION_EDGE=" + source.path + "|" + configuration.name + "|" + dependency.path)
                                        }
                                    }
                                }
                            }
                            def app = root.project(":app")
                            app.configurations.findAll { configuration ->
                                (configuration.name == "compileClasspath" ||
                                    configuration.name.endsWith("CompileClasspath")) &&
                                    !explicitTestOnlyConfiguration(configuration.name)
                            }.sort { it.name }.each { configuration ->
                                println("COCKPIT_APP_PRODUCTION_COMPILE_CLASSPATH=" + configuration.name)
                                try {
                                    def resolution = configuration.incoming.resolutionResult
                                    def unresolved = resolution.allDependencies.findAll { dependency ->
                                        dependency instanceof org.gradle.api.artifacts.result.UnresolvedDependencyResult
                                    }
                                    if (!unresolved.isEmpty()) {
                                        throw new GradleException(
                                            "Unresolved dependencies: " +
                                                unresolved.collect { it.attempted.displayName }.sort().join(", "),
                                        )
                                    }
                                    resolution.allComponents.collect { component -> component.id }
                                        .findAll { identifier ->
                                            identifier instanceof org.gradle.api.artifacts.component.ProjectComponentIdentifier &&
                                                identifier.projectPath != app.path
                                        }
                                        .collect { identifier -> identifier.projectPath }
                                        .toSet()
                                        .sort()
                                        .each { projectPath ->
                                            println("COCKPIT_APP_COMPILE_PROJECT=" + configuration.name + "|" + projectPath)
                                        }
                                } catch (Exception failure) {
                                    throw new GradleException(
                                        "Unable to resolve :app production compile classpath " + configuration.name +
                                            ": " + failure.message,
                                        failure,
                                    )
                                }
                            }
                        }
                    }
                }
                """.trimIndent()
        }

        private data class EvaluatedModel(
            val projects: Set<String>,
            val dependencies: Set<EvaluatedProjectDependency>,
            val effectiveProductionClasspathDependencies: Set<EvaluatedProjectDependency>,
            val appProductionCompileVisibility: Map<String, Set<String>>,
        )

        private data class EvaluatedProjectDependency(
            val source: String,
            val configuration: String,
            val target: String,
        )
    }

    private companion object {
        val structuralProjectPaths = setOf(
            ":",
            ":agent",
            ":core",
            ":data",
            ":integration",
            ":platform",
            ":security",
            ":spikes",
        )

        val productionProjects = setOf(
            ":app",
            ":presentation",
            ":core:domain",
            ":core:application-api",
            ":core:application",
            ":core:runtime-api",
            ":core:runtime",
            ":agent:skill-api",
            ":agent:skill-runtime",
            ":integration:execution-api",
            ":integration:ssh",
            ":integration:provider-api",
            ":integration:provider",
            ":data:persistence-api",
            ":data:persistence-room",
            ":data:projection-models",
            ":data:projection",
            ":security:byte-renderer-api",
            ":security:byte-renderer",
            ":security:vault-api",
            ":security:vault",
            ":security:permission-api",
            ":security:permission",
            ":security:egress-api",
            ":security:egress",
            ":platform:background-api",
            ":platform:background",
            ":platform:android",
        )

        val expectedProjects = productionProjects + setOf(
            ":architecture-tests",
            ":security-tests",
            ":spikes:ssh-transport",
            ":test-support",
        )

        val requiredAppProductionCompileClasspaths = setOf(
            "debugCompileClasspath",
            "releaseCompileClasspath",
        )

        val expectedAppCompileVisibility = setOf(
            ":presentation",
            ":platform:android",
        )

        val expectedEdges = setOf(
            Edge(":app", ":presentation"),
            Edge(":app", ":platform:android"),
            Edge(":presentation", ":core:application-api"),
            Edge(":presentation", ":data:projection-models"),
            Edge(":presentation", ":security:byte-renderer-api"),
            Edge(":core:application-api", ":core:domain"),
            Edge(":core:application-api", ":integration:provider-api"),
            Edge(":core:application-api", ":data:projection-models"),
            Edge(":core:application-api", ":security:byte-renderer-api"),
            Edge(":core:application", ":core:application-api"),
            Edge(":core:application", ":core:domain"),
            Edge(":core:application", ":core:runtime-api"),
            Edge(":core:application", ":security:byte-renderer"),
            Edge(":core:application", ":security:vault-api"),
            Edge(":core:application", ":data:persistence-api"),
            Edge(":core:application", ":data:projection-models"),
            Edge(":core:runtime", ":core:domain"),
            Edge(":core:runtime", ":core:runtime-api"),
            Edge(":core:runtime", ":agent:skill-api"),
            Edge(":core:runtime", ":security:permission-api"),
            Edge(":core:runtime", ":security:egress-api"),
            Edge(":core:runtime", ":security:vault-api"),
            Edge(":core:runtime", ":integration:provider-api"),
            Edge(":core:runtime", ":integration:execution-api"),
            Edge(":core:runtime", ":data:persistence-api"),
            Edge(":core:runtime", ":platform:background-api"),
            Edge(":core:runtime-api", ":core:domain"),
            Edge(":agent:skill-api", ":core:domain"),
            Edge(":agent:skill-runtime", ":agent:skill-api"),
            Edge(":agent:skill-runtime", ":core:domain"),
            Edge(":agent:skill-runtime", ":security:permission-api"),
            Edge(":integration:ssh", ":core:domain"),
            Edge(":integration:ssh", ":integration:execution-api"),
            Edge(":integration:ssh", ":security:vault-api"),
            Edge(":integration:ssh", ":data:persistence-api"),
            Edge(":data:persistence-room", ":data:persistence-api"),
            Edge(":data:persistence-room", ":core:domain"),
            Edge(":data:projection", ":core:domain"),
            Edge(":data:projection", ":data:persistence-api"),
            Edge(":data:projection", ":data:projection-models"),
            Edge(":security:byte-renderer", ":security:byte-renderer-api"),
            Edge(":security:byte-renderer", ":core:domain"),
            Edge(":security:vault", ":core:domain"),
            Edge(":security:vault", ":security:vault-api"),
            Edge(":security:vault-api", ":core:domain"),
            Edge(":security:vault-api", ":integration:execution-api"),
            Edge(":security:vault-api", ":integration:provider-api"),
            Edge(":security:permission", ":security:permission-api"),
            Edge(":security:permission", ":core:domain"),
            Edge(":security:permission-api", ":core:domain"),
            Edge(":security:egress", ":security:egress-api"),
            Edge(":security:egress", ":core:domain"),
            Edge(":security:egress", ":data:persistence-api"),
            Edge(":security:egress-api", ":core:domain"),
            Edge(":integration:provider-api", ":core:domain"),
            Edge(":integration:provider", ":integration:provider-api"),
            Edge(":integration:execution-api", ":core:domain"),
            Edge(":data:persistence-api", ":core:domain"),
            Edge(":data:projection-models", ":core:domain"),
            Edge(":platform:background", ":platform:background-api"),
            Edge(":platform:background", ":core:runtime-api"),
            Edge(":platform:background", ":data:projection-models"),
            Edge(":platform:background-api", ":core:domain"),
            Edge(":platform:android", ":core:application"),
            Edge(":platform:android", ":core:runtime"),
            Edge(":platform:android", ":agent:skill-runtime"),
            Edge(":platform:android", ":security:permission"),
            Edge(":platform:android", ":security:egress"),
            Edge(":platform:android", ":security:vault"),
            Edge(":platform:android", ":integration:provider"),
            Edge(":platform:android", ":integration:ssh"),
            Edge(":platform:android", ":data:persistence-room"),
            Edge(":platform:android", ":data:projection"),
            Edge(":platform:android", ":platform:background"),
        )
    }
}
