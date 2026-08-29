package dev.cockpit.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.io.path.exists

class ModuleGraphTest {
    @Test
    fun presentationCannotReachAdapters() {
        val graph = GradleModuleGraph.read(projectRoot())

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
    fun rejectsProjectDependencySyntaxItCannotValidate() {
        val root = projectRoot()

        assertGraphRejects(root.resolve("settings.gradle.kts")) {
            "$it\ninclude(\":unexpected\", \":also-unexpected\")\n"
        }
        assertGraphRejects(root.resolve("app/build.gradle.kts")) {
            "$it\n\ndependencies {\n    implementation(projects.presentation)\n}\n"
        }
    }

    private fun projectRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { it.resolve(".git").exists() }

    private fun assertGraphRejects(file: Path, mutate: (String) -> String) {
        val original = Files.readString(file)
        try {
            Files.writeString(file, mutate(original))
            assertThrows(AssertionError::class.java) { GradleModuleGraph.read(projectRoot()) }
        } finally {
            Files.writeString(file, original)
        }
    }

    private data class Edge(val source: String, val target: String)

    private class GradleModuleGraph(
        val declaredProjects: Set<String>,
        val allowedEdges: Set<Edge>,
        val productionEdges: Set<Edge>,
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
                val declaredProjects = parseSettings(root.resolve("settings.gradle.kts"))
                val manifest = parseManifest(root.resolve("gradle/module-graph.txt"))
                val productionEdges = declaredProjects
                    .intersect(productionProjects)
                    .flatMap { source -> parseCanonicalProjectDependencies(root, source) }
                    .toSet()
                return GradleModuleGraph(declaredProjects, manifest, productionEdges)
            }

            private fun parseSettings(file: Path): Set<String> {
                assertTrue(file.exists(), "settings.gradle.kts must declare the module graph.")
                return Files.readString(file).lineSequence().mapIndexedNotNull { index, rawLine ->
                    val line = rawLine.trim()
                    includeDeclaration.matchEntire(line)?.groupValues?.get(1) ?: if (line.startsWith("include(")) {
                        throw AssertionError("Unsupported project inclusion at line ${index + 1}: $line")
                    } else {
                        null
                    }
                }.toSet()
            }

            private fun parseManifest(file: Path): Set<Edge> {
                assertTrue(file.exists(), "The allowed-edge manifest is required.")
                return Files.readString(file).lineSequence().filter { it.isNotBlank() }.mapIndexed { index, rawLine ->
                    val match = manifestDeclaration.matchEntire(rawLine.trim())
                        ?: throw AssertionError("Unsupported allowed-edge manifest entry at line ${index + 1}: $rawLine")
                    Edge(match.groupValues[1], match.groupValues[2])
                }.toSet()
            }

            private fun parseCanonicalProjectDependencies(root: Path, source: String): List<Edge> {
                val buildFile = root.resolve(source.removePrefix(":").replace(':', '/')).resolve("build.gradle.kts")
                assertTrue(buildFile.exists(), "Production module $source must declare its Gradle build file.")
                return Files.readString(buildFile).lineSequence().mapIndexedNotNull { index, rawLine ->
                    val line = rawLine.trim()
                    canonicalProjectDependency.matchEntire(line)?.let { match ->
                        Edge(source, match.groupValues[1])
                    } ?: if ("project(" in line || "projects." in line) {
                        throw AssertionError("Unsupported project-dependency expression in $source at line ${index + 1}: $line")
                    } else {
                        null
                    }
                }.toList()
            }

            private val includeDeclaration = Regex("^include\\(\\\"(:[^\\\"]+)\\\"\\)$")
            private val manifestDeclaration = Regex("^(:[A-Za-z0-9-]+(?::[A-Za-z0-9-]+)*) -> (:[A-Za-z0-9-]+(?::[A-Za-z0-9-]+)*)$")
            private val canonicalProjectDependency =
                Regex("^implementation\\(project\\(\\\"(:[A-Za-z0-9-]+(?::[A-Za-z0-9-]+)*)\\\"\\)\\)$")
        }
    }

    private companion object {
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
