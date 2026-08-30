package dev.cockpit.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchitectureEvidenceTest {
    @Test
    fun requiresAcceptedFoundationAdrsAndCiTasks() {
        val root = projectRoot()
        val failures = mutableListOf<String>()

        foundationAdrs.forEach { evidence ->
            val file = root.resolve(evidence.path)
            if (!file.exists()) {
                failures += "Missing ADR: ${evidence.path}"
            } else {
                val content = Files.readString(file)
                requireExactlyOne(content, Regex("(?m)^Status: Accepted\\s*$"), "${evidence.path} must declare exactly one `Status: Accepted`", failures)
                requireArchitectureLink(content, root, file, "${evidence.path} must link to the Approved/Frozen System Architecture", failures)
                evidence.sections.forEach { section ->
                    requireContains(content, section, "${evidence.path} must identify relevant approved section $section", failures)
                }
                requireContains(content, "Approved/Frozen System Architecture remains authoritative", "${evidence.path} must preserve System Architecture authority", failures)
                requireContains(content, "Architecture Change Request", "${evidence.path} must require an explicit Architecture Change Request for downstream conflicts", failures)
                evidence.decisionTerms.forEach { term ->
                    requireContains(content, term, "${evidence.path} must preserve approved decision: $term", failures)
                }
            }
        }

        val workflow = root.resolve(".github/workflows/android.yml")
        if (!workflow.exists()) {
            failures += "Missing CI workflow: .github/workflows/android.yml"
        } else {
            val content = Files.readString(workflow)
            requireContains(content, "push:", "CI workflow must run on push", failures)
            requireContains(content, "pull_request:", "CI workflow must run on pull_request", failures)
            requireAbsent(content, "pull_request_target", "CI workflow must never use pull_request_target", failures)
            requireExactlyOne(content, Regex("(?m)^permissions:\\s*\\r?$"), "CI workflow must have one top-level permissions declaration", failures)
            requireExactlyOne(content, Regex("(?m)^\\s+contents: read\\s*$"), "CI workflow permissions must be read-only (`contents: read`)", failures)
            requireExactlyOne(content, Regex("(?m)^\\s+timeout-minutes: [1-9]\\d*\\s*$"), "CI workflow must set one finite job timeout", failures)
            requireExactlyOne(content, Regex("(?m)^\\s*-?\\s*uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1\\s*$"), "CI workflow must pin actions/checkout", failures)
            requireExactlyOne(content, Regex("(?m)^\\s*-?\\s*uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961\\s*$"), "CI workflow must pin actions/setup-java", failures)
            requireContains(content, "distribution: temurin", "CI workflow must select Temurin", failures)
            requireContains(content, "java-version: '17'", "CI workflow must select Java 17", failures)
            requireContains(content, "cache: gradle", "CI workflow must use the permitted Gradle cache", failures)
            requireContains(content, "check-latest: false", "CI workflow must disable check-latest", failures)
            requireContains(content, "platforms;android-36", "CI workflow must install Android API 36", failures)
            requireContains(content, "build-tools;36.0.0", "CI workflow must install Android build-tools 36.0.0", failures)
            requireContains(content, "./gradlew test verifyArchitecture :app:assembleDebug lint", "CI workflow must execute the required wrapper tasks", failures)
            listOf("continue-on-error", "|| true", "--continue").forEach { prohibited ->
                requireAbsent(content, prohibited, "CI workflow Gradle step must be fail-fast and not contain `$prohibited`", failures)
            }
        }

        val rootBuild = root.resolve("build.gradle.kts")
        if (!rootBuild.exists()) {
            failures += "Missing root build.gradle.kts"
        } else {
            val content = Files.readString(rootBuild)
            requireExactlyOne(content, Regex("tasks\\.register\\(\\\"verifyArchitecture\\\"\\)"), "Root build must register exactly one verifyArchitecture task", failures)
            requireContains(content, "dependsOn(\":architecture-tests:test\")", "verifyArchitecture must depend on :architecture-tests:test", failures)
        }

        val detekt = root.resolve("config/detekt/detekt.yml")
        if (!detekt.exists()) {
            failures += "Missing fail-closed Detekt configuration: config/detekt/detekt.yml"
        } else {
            val content = Files.readString(detekt)
            requireContains(content, "maxIssues: 0", "Detekt configuration must fail closed with build.maxIssues: 0", failures)
            requireContains(content, "validation: true", "Detekt configuration must enable validation", failures)
        }

        val gradlewMode = gitMode(root, "gradlew")
        if (gradlewMode != "100755") {
            failures += "gradlew must be tracked executable (100755), but was ${gradlewMode ?: "untracked"}"
        }

        failures += verifyArchitectureResolvesAsTask(root)
        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\\n"))
    }

    private fun verifyArchitectureResolvesAsTask(root: Path): List<String> {
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd", "/c", "gradlew.bat", "verifyArchitecture", "--dry-run", "--no-daemon", "--console=plain")
        } else {
            listOf("./gradlew", "verifyArchitecture", "--dry-run", "--no-daemon", "--console=plain")
        }
        val output = Files.createTempFile("cockpit-verify-architecture-", ".txt")
        try {
            val process = ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
            val completed = process.waitFor(60, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            val result = Files.readString(output)
            return if (completed && process.exitValue() == 0 && result.contains(":architecture-tests:test")) {
                emptyList()
            } else {
                listOf("verifyArchitecture must resolve as a Gradle task depending on :architecture-tests:test: $result")
            }
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun gitMode(root: Path, path: String): String? {
        val process = ProcessBuilder("git", "ls-files", "--stage", "--", path)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(10, TimeUnit.SECONDS)
        if (!completed || process.exitValue() != 0) return null
        return process.inputStream.bufferedReader().readLine()?.substringBefore(' ')
    }

    private fun requireContains(content: String, value: String, message: String, failures: MutableList<String>) {
        if (!content.contains(value)) failures += message
    }

    private fun requireArchitectureLink(content: String, root: Path, adr: Path, message: String, failures: MutableList<String>) {
        val authoritativeSource = root.resolve(architectureSource).normalize()
        val links = Regex("\\]\\(([^)]+)\\)").findAll(content).map { it.groupValues[1] }
        if (links.none { adr.parent.resolve(it).normalize() == authoritativeSource }) failures += message
    }

    private fun requireAbsent(content: String, value: String, message: String, failures: MutableList<String>) {
        if (content.contains(value)) failures += message
    }

    private fun requireExactlyOne(content: String, declaration: Regex, message: String, failures: MutableList<String>) {
        if (declaration.findAll(content).count() != 1) failures += message
    }

    private fun projectRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { it.resolve(".git").exists() }

    private data class AdrEvidence(
        val path: String,
        val sections: List<String>,
        val decisionTerms: List<String>,
    )

    private companion object {
        const val architectureSource = "docs/superpowers/specs/2026-08-30-system-architecture-v0.1.md"
        val foundationAdrs = listOf(
            AdrEvidence("docs/adr/ADR-001-module-boundaries.md", listOf("Section 5", "Section 30"), listOf("modular monolith", "Ports/Adapters", "module dependency boundaries")),
            AdrEvidence("docs/adr/ADR-002-single-writer-runtime.md", listOf("Section 7", "Section 9", "Section 10", "Section 30"), listOf("RunCoordinator", "sole Run-state writer", "long I/O", "version/attempt validation")),
            AdrEvidence("docs/adr/ADR-003-persistence-event-ledger.md", listOf("Section 12", "Section 13", "Section 30"), listOf("authoritative relational facts", "append-only Runtime Event Ledger", "encrypted content/evidence", "rebuildable projections")),
            AdrEvidence("docs/adr/ADR-004-projection-boundary.md", listOf("Section 12.4", "Section 13.4", "Section 14", "Section 30"), listOf("global event ordinal", "per-Run sequence", "expected version", "committed facts", "never becoming authority")),
        )
    }
}
