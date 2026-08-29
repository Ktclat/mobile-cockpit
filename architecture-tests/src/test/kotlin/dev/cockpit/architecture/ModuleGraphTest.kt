package dev.cockpit.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        val root = projectRoot()
        val fixtureDirectory = root.resolve("architecture-graph-whitespace-fixture")
        assertFalse(Files.exists(fixtureDirectory), "The whitespace-inclusion fixture directory must not already exist.")
        Files.createDirectory(fixtureDirectory)
        try {
            assertGraphRejects(root.resolve("settings.gradle.kts")) {
                "$it\ninclude (\":architecture-graph-whitespace-fixture\")\n"
            }
        } finally {
            Files.deleteIfExists(fixtureDirectory)
        }
    }

    @Test
    fun rejectsProductionDependencyInjectedFromRootBuildLogic() {
        assertGraphRejects(projectRoot().resolve("build.gradle.kts")) {
            "$it\n\nproject(\":presentation\") {\n    pluginManager.withPlugin(\"java-library\") {\n        dependencies.add(\n            \"implementation\",\n            dependencies.project(mapOf(\"path\" to \":spikes:ssh-transport\")),\n        )\n    }\n}\n"
        }
    }

    @Test
    fun rejectsNonTestProjectDependencyFromStructuralContainer() {
        assertGraphRejects(projectRoot().resolve("build.gradle.kts")) {
            "$it\n\nproject(\":core\") {\n    val architectureGraph = configurations.maybeCreate(\"architectureGraph\")\n    dependencies.add(\n        architectureGraph.name,\n        dependencies.project(mapOf(\"path\" to \":spikes:ssh-transport\")),\n    )\n}\n"
        }
    }

    private fun projectRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { it.resolve(".git").exists() }

    private fun assertGraphRejects(file: Path, mutate: (String) -> String) {
        val original = Files.readString(file)
        try {
            Files.writeString(file, mutate(original))
            val graph = GradleModuleGraph.read(projectRoot())
            try {
                assertFrozenGraph(graph)
            } catch (_: AssertionError) {
                return
            }
            fail<Nothing>("Expected graph rejection, but evaluated production edges were ${graph.productionEdges}.")
        } finally {
            Files.writeString(file, original)
        }
    }

    private data class Edge(val source: String, val target: String)

    private class GradleModuleGraph(
        val declaredProjects: Set<String>,
        val allowedEdges: Set<Edge>,
        val productionEdges: Set<Edge>,
        val nonTestStructuralEdges: Set<Edge>,
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
            fun read(root: Path): GradleModuleGraph {
                val evaluatedModel = readEvaluatedModel(root)
                val manifest = parseManifest(root.resolve("gradle/module-graph.txt"))
                assertTrue(
                    structuralProjectPaths.all { it in evaluatedModel.projects },
                    "Gradle must retain its deterministic root and structural container projects.",
                )
                val productionEdges = evaluatedModel.dependencies
                    .filter { it.source in productionProjects && !it.configuration.contains("test", ignoreCase = true) }
                    .map { Edge(it.source, it.target) }
                    .toSet()
                val nonTestStructuralEdges = evaluatedModel.dependencies
                    .filter { it.source in structuralProjectPaths && !it.configuration.contains("test", ignoreCase = true) }
                    .map { Edge(it.source, it.target) }
                    .toSet()
                return GradleModuleGraph(
                    evaluatedModel.projects - structuralProjectPaths,
                    manifest,
                    productionEdges,
                    nonTestStructuralEdges,
                )
            }

            private fun readEvaluatedModel(root: Path): EvaluatedModel {
                val initScript = Files.createTempFile("cockpit-module-graph-", ".gradle")
                val outputFile = Files.createTempFile("cockpit-module-graph-output-", ".txt")
                try {
                    Files.writeString(initScript, evaluatedModelInitScript)
                    val command = if (System.getProperty("os.name").startsWith("Windows")) {
                        mutableListOf("cmd", "/c", "gradlew.bat")
                    } else {
                        mutableListOf("./gradlew")
                    }.apply {
                        addAll(listOf("--no-daemon", "--console=plain", "-I", initScript.toString(), "cockpitModuleGraphSnapshot"))
                    }
                    val process = ProcessBuilder(command)
                        .directory(root.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(outputFile.toFile())
                        .start()
                    val completed = process.waitFor(60, TimeUnit.SECONDS)
                    if (!completed) {
                        process.destroyForcibly()
                        process.waitFor(10, TimeUnit.SECONDS)
                    }
                    val output = Files.readString(outputFile)
                    assertTrue(completed, "Timed out while reading the evaluated Gradle module graph.\n$output")
                    assertEquals(0, process.exitValue(), "Unable to read the evaluated Gradle module graph:\n$output")
                    return parseEvaluatedModel(output)
                } finally {
                    Files.deleteIfExists(initScript)
                    Files.deleteIfExists(outputFile)
                }
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
                output.lineSequence().forEach { line ->
                    when {
                        line.startsWith(projectMarker) -> projects += line.removePrefix(projectMarker)
                        line.startsWith(dependencyMarker) -> {
                            val fields = line.removePrefix(dependencyMarker).split('|')
                            assertEquals(3, fields.size, "Malformed evaluated project dependency: $line")
                            dependencies += EvaluatedProjectDependency(fields[0], fields[1], fields[2])
                        }
                    }
                }
                assertTrue(projects.isNotEmpty(), "The evaluated Gradle module graph produced no project paths.\n$output")
                return EvaluatedModel(projects, dependencies)
            }

            private val manifestDeclaration = Regex("^(:[A-Za-z0-9-]+(?::[A-Za-z0-9-]+)*) -> (:[A-Za-z0-9-]+(?::[A-Za-z0-9-]+)*)$")
            private const val projectMarker = "COCKPIT_MODULE_PROJECT="
            private const val dependencyMarker = "COCKPIT_MODULE_EDGE="
            private val evaluatedModelInitScript =
                """
                gradle.projectsEvaluated {
                    gradle.rootProject.tasks.register("cockpitModuleGraphSnapshot") {
                        doLast {
                            def root = project.rootProject
                            root.allprojects.collect { it.path }.sort().each {
                                println("COCKPIT_MODULE_PROJECT=" + it)
                            }
                            root.allprojects.each { source ->
                                source.configurations.each { configuration ->
                                    configuration.dependencies.withType(org.gradle.api.artifacts.ProjectDependency).each { dependency ->
                                        println("COCKPIT_MODULE_EDGE=" + source.path + "|" + configuration.name + "|" + dependency.path)
                                    }
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
