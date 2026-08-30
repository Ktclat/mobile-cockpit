package dev.cockpit.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.exists

class AndroidModuleToolchainPolicyTest {
    @Test
    fun androidModulesUseJava17Toolchains() {
        assertEquals(
            mapOf(
                ":app" to 17,
                ":presentation" to 17,
                ":platform:android" to 17,
            ),
            readJavaToolchains(projectRoot()),
            "Every Android module must explicitly expose a Java 17 toolchain through Gradle's evaluated java extension.",
        )
    }

    private fun readJavaToolchains(root: Path): Map<String, Int?> {
        val initScript = Files.createTempFile("cockpit-java-toolchain-", ".gradle")
        val outputFile = Files.createTempFile("cockpit-java-toolchain-output-", ".txt")
        try {
            Files.writeString(initScript, evaluatedToolchainInitScript)
            val command = if (System.getProperty("os.name").startsWith("Windows")) {
                mutableListOf("cmd", "/c", "gradlew.bat")
            } else {
                mutableListOf("./gradlew")
            }.apply {
                addAll(listOf("--no-daemon", "--console=plain", "-I", initScript.toString(), "cockpitJavaToolchainSnapshot"))
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
            assertTrue(completed, "Timed out while reading Android Java toolchains.\n$output")
            assertEquals(0, process.exitValue(), "Unable to read Android Java toolchains:\n$output")
            return output.lineSequence().mapNotNull { line ->
                snapshotDeclaration.matchEntire(line)?.let { match ->
                    match.groupValues[1] to match.groupValues[2].toIntOrNull()
                }
            }.toMap()
        } finally {
            Files.deleteIfExists(initScript)
            Files.deleteIfExists(outputFile)
        }
    }

    private fun projectRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { it.resolve(".git").exists() }

    private companion object {
        val snapshotDeclaration = Regex("COCKPIT_JAVA_TOOLCHAIN=([^|]+)\\|(.+)")
        val evaluatedToolchainInitScript =
            """
            gradle.projectsEvaluated {
                [":app", ":presentation", ":platform:android"].each { path ->
                    def javaExtension = gradle.rootProject.project(path).extensions.findByName("java")
                    def languageVersion = javaExtension?.toolchain?.languageVersion?.orNull?.asInt()
                    println("COCKPIT_JAVA_TOOLCHAIN=" + path + "|" + languageVersion)
                }
            }

            gradle.projectsLoaded {
                gradle.rootProject.tasks.register("cockpitJavaToolchainSnapshot")
            }
            """.trimIndent()
    }
}
