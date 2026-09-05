package dev.cockpit.architecture

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.exists
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ArchitectureEvidenceTest {
    @Test
    fun rejectsCommentedWorkflowTriggers() {
        assertWorkflowRejected(
            canonicalWorkflow.replace(
                "on:\n  workflow_dispatch:\n  pull_request:\n  push:\n    branches:\n      - main",
                "# on:\n#   workflow_dispatch:\n#   pull_request:\n#   push:\n#     branches:\n#       - main",
            ),
        )
    }

    @Test
    fun rejectsEchoInsteadOfWorkflowGradleExecution() {
        assertWorkflowRejected(
            canonicalWorkflow.replace(
                "run: ./gradlew test verifyArchitecture :app:assembleDebug lint",
                "run: echo \"./gradlew test verifyArchitecture :app:assembleDebug lint\"",
            ),
        )
    }

    @Test
    fun rejectsWorkflowSetupJavaValuesUnderAnUnrelatedStep() {
        assertWorkflowRejected(
            canonicalWorkflow.replace(
                "      - uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961\n        with:",
                "      - name: unrelated step\n        with:\n          distribution: temurin\n          java-version: '17'\n          cache: gradle\n          check-latest: false\n      - uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961\n        with:",
            ),
        )
    }

    @Test
    fun rejectsAmbiguousDuplicateSetupJavaDeclaration() {
        assertWorkflowRejected(canonicalWorkflow.replace("          distribution: temurin", "          distribution: temurin\n          distribution: temurin"))
    }

    @Test
    fun rejectsDetektValuesOutsideTheirRequiredSections() {
        assertDetektRejected(
            """
            build:
              validation: true
            config:
              maxIssues: 0
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun reapsTimedOutEvidenceProcessWrapperAndChild() {
        val pidFile = Files.createTempFile("cockpit-evidence-process-", ".pids")
        try {
            val result = runCatching {
                runEvidenceProcess(timeoutWrapperCommand(pidFile), projectRoot(), 1, TimeUnit.SECONDS)
            }

            val pids = Files.readString(pidFile).trim().split(',').map(String::toLong)
            assertTrue(pids.size == 2, "The wrapper must record both its own PID and its child's PID")
            assertTrue(pids.none(::isProcessAlive), "Timed-out evidence processes must leave no wrapper or child alive")
            assertTrue(result.isSuccess, "Evidence output must be consumed and deleted only after process cleanup")
            assertFalse(result.getOrThrow().completed, "The long-lived wrapper must take the timeout path")
        } finally {
            readRecordedPids(pidFile).forEach(::terminateForTest)
            Files.deleteIfExists(pidFile)
        }
    }

    @Test
    fun deletesKnownOutputWhenEvidenceProcessCannotStart() {
        val output = Files.createTempFile("cockpit-evidence-process-start-failure-", ".txt")
        try {
            assertThrows(IOException::class.java) {
                runEvidenceProcess(
                    command = listOf("cockpit-command-that-does-not-exist"),
                    root = projectRoot(),
                    timeout = 1,
                    unit = TimeUnit.SECONDS,
                    output = output,
                )
            }
            assertFalse(Files.exists(output), "A failed ProcessBuilder.start must delete its known output temp file")
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun interruptionReapsEvidenceProcessTreeAndDeletesKnownOutput() {
        val pidFile = Files.createTempFile("cockpit-evidence-process-interrupt-", ".pids")
        val output = Files.createTempFile("cockpit-evidence-process-interrupt-", ".txt")
        try {
            assertThrows(InterruptedException::class.java) {
                runEvidenceProcess(
                    command = timeoutWrapperCommand(pidFile),
                    root = projectRoot(),
                    timeout = 30,
                    unit = TimeUnit.SECONDS,
                    output = output,
                    afterStart = {
                        awaitPidFile(pidFile)
                        Thread.currentThread().interrupt()
                    },
                )
            }
            assertTrue(Thread.interrupted(), "Interrupted evidence processing must restore the interrupt status")
            assertTrue(awaitPidFile(pidFile).none(::isProcessAlive), "Interrupted evidence processing must reap wrapper and child")
            assertFalse(Files.exists(output), "Interrupted evidence processing must delete its known output temp file")
        } finally {
            Thread.interrupted()
            readRecordedPids(pidFile).forEach(::terminateForTest)
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun reapsCapturedChildWhenWrapperExitsBeforeFinally() {
        val pidFile = Files.createTempFile("cockpit-evidence-process-wrapper-exit-", ".pids")
        val output = Files.createTempFile("cockpit-evidence-process-wrapper-exit-", ".txt")
        try {
            runEvidenceProcess(
                timeoutWrapperCommand(pidFile),
                projectRoot(),
                30,
                TimeUnit.SECONDS,
                output,
                afterStart = { wrapper ->
                    awaitPidFile(pidFile)
                    wrapper.destroyForcibly()
                },
            )
            assertTrue(awaitPidFile(pidFile).none(::isProcessAlive), "A dead wrapper must not hide a live captured child")
            assertFalse(Files.exists(output), "Output must be deleted only after the captured tree is reaped")
        } finally {
            readRecordedPids(pidFile).forEach(::terminateForTest)
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun readsEvidenceOutputOnlyAfterEveryCapturedProcessExits() {
        val pidFile = Files.createTempFile("cockpit-evidence-process-read-order-", ".pids")
        val output = Files.createTempFile("cockpit-evidence-process-read-order-", ".txt")
        var recordedPids = emptyList<Long>()
        try {
            runEvidenceProcess(
                command = timeoutWrapperCommand(pidFile),
                root = projectRoot(),
                timeout = 30,
                unit = TimeUnit.SECONDS,
                output = output,
                afterStart = { wrapper ->
                    recordedPids = awaitPidFile(pidFile)
                    wrapper.destroyForcibly()
                },
                readOutput = { path ->
                    assertTrue(
                        recordedPids.none(::isProcessAlive),
                        "Evidence output must not be read while any captured descendant is alive",
                    )
                    Files.readString(path)
                },
            )
        } finally {
            recordedPids.forEach(::terminateForTest)
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun preservesPrimaryFailureAndSuppressesCleanupFailure() {
        val output = Files.createTempFile("cockpit-evidence-process-primary-failure-", ".txt")
        val primary = IllegalStateException("primary evidence failure")
        try {
            val thrown = assertThrows(IllegalStateException::class.java) {
                runEvidenceProcess(
                    command = successfulEvidenceCommand(),
                    root = projectRoot(),
                    timeout = 5,
                    unit = TimeUnit.SECONDS,
                    output = output,
                    afterStart = { throw primary },
                    deleteOutput = { _, _ -> false },
                    deferOutputDeletion = { _, _, _ -> DeferredDeletionOutcome.DELETE_FAILED },
                )
            }

            assertTrue(thrown === primary, "Cleanup failure must not replace the original evidence failure")
            val cleanupFailure = thrown.suppressed.singleOrNull()
            assertTrue(
                cleanupFailure is EvidenceProcessCleanupException,
                "Cleanup failure must be attached to the original failure as suppressed evidence",
            )
            assertTrue(
                (cleanupFailure as EvidenceProcessCleanupException).deferredDeletionOutcome ==
                    DeferredDeletionOutcome.DELETE_FAILED,
                "The suppressed cleanup failure must expose the bounded deferred deletion outcome",
            )
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun boundsAndReportsDeferredDeletionWhenCapturedProcessStaysAlive() {
        val output = Files.createTempFile("cockpit-evidence-process-deferred-timeout-", ".txt")
        val process = ProcessBuilder(longRunningEvidenceCommand()).start()
        try {
            val outcome = scheduleDeferredOutputDeletion(
                output = output,
                capturedTree = mapOf(process.pid() to process.toHandle()),
                interruptState = InterruptState(),
                timeout = 50,
                unit = TimeUnit.MILLISECONDS,
            )

            assertTrue(
                outcome == DeferredDeletionOutcome.RETAINED_LIVE_PROCESS,
                "A bounded deferred cleanup must report that output was retained for a live process",
            )
            assertTrue(Files.exists(output), "Deferred cleanup must not delete output while its process is alive")
        } finally {
            terminateForTest(process.pid())
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun reportsDeferredDeletionFailureAfterCapturedProcessesExit() {
        val output = Files.createTempFile("cockpit-evidence-process-deferred-delete-failure-", ".txt")
        try {
            val outcome = scheduleDeferredOutputDeletion(
                output = output,
                capturedTree = emptyMap(),
                interruptState = InterruptState(),
                timeout = 50,
                unit = TimeUnit.MILLISECONDS,
                deleteOutput = { _, _ -> false },
            )

            assertTrue(
                outcome == DeferredDeletionOutcome.DELETE_FAILED,
                "A final deferred delete failure must be returned to the caller rather than ignored",
            )
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun pureMutationValidatorsLeaveTrackedEvidenceUnchanged() {
        val root = projectRoot()
        val workflow = root.resolve(".github/workflows/android.yml")
        val detekt = root.resolve("config/detekt/detekt.yml")
        val originalWorkflow = Files.readString(workflow)
        val originalDetekt = Files.readString(detekt)

        assertWorkflowRejected(originalWorkflow.replace("on:", "# on:"))
        assertDetektRejected(originalDetekt.replace("maxIssues: 0", "maxIssues: 1"))

        assertTrue(Files.readString(workflow) == originalWorkflow, "Pure workflow validation must not write tracked evidence")
        assertTrue(Files.readString(detekt) == originalDetekt, "Pure Detekt validation must not write tracked evidence")
    }

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
            failures += validateWorkflow(Files.readString(workflow))
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
            failures += validateDetekt(Files.readString(detekt))
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
        val result = runEvidenceProcess(command, root, 60, TimeUnit.SECONDS)
        return if (result.completed && result.exitCode == 0 && result.output.contains(":architecture-tests:test")) {
            emptyList()
        } else {
            listOf("verifyArchitecture must resolve as a Gradle task depending on :architecture-tests:test: ${result.output}")
        }
    }

    private fun gitMode(root: Path, path: String): String? {
        val gitCommand = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf(
                "cmd", "/d", "/c", "git",
                "-c", "core.excludesFile=",
                "-c", "safe.directory=${root.toAbsolutePath()}",
                "ls-files", "--stage", "--", path,
            )
        } else {
            listOf(
                "git",
                "-c", "core.excludesFile=",
                "-c", "safe.directory=${root.toAbsolutePath()}",
                "ls-files", "--stage", "--", path,
            )
        }
        val result = runEvidenceProcess(
            gitCommand,
            root,
            10,
            TimeUnit.SECONDS,
        )
        if (!result.completed || result.exitCode != 0) {
            return "git-command-failed(completed=${result.completed}, exit=${result.exitCode}, output=${result.output.take(240)})"
        }
        return result.output.lineSequence().firstOrNull()?.substringBefore(' ')
    }

    private fun runEvidenceProcess(
        command: List<String>,
        root: Path,
        timeout: Long,
        unit: TimeUnit,
        output: Path = Files.createTempFile("cockpit-evidence-process-", ".txt"),
        afterStart: (Process) -> Unit = {},
        readOutput: (Path) -> String = { path -> Files.readString(path) },
        deleteOutput: (Path, InterruptState) -> Boolean = ::deleteEvidenceOutput,
        deferOutputDeletion: (Path, Map<Long, ProcessHandle>, InterruptState) -> DeferredDeletionOutcome =
            { path, tree, state -> scheduleDeferredOutputDeletion(path, tree, state) },
    ): EvidenceProcessResult {
        var process: Process? = null
        val capturedTree = linkedMapOf<Long, ProcessHandle>()
        val interruptState = InterruptState()
        var result: EvidenceProcessResult? = null
        var primaryFailure: Throwable? = null
        try {
            val started = ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
            process = started
            captureProcessTree(started, capturedTree)
            afterStart(started)
            captureProcessTree(started, capturedTree)
            val completed = waitForEvidenceProcess(started, timeout, unit, capturedTree)
            if (!terminateAndReapProcessTree(started, capturedTree, interruptState)) {
                throw EvidenceProcessCleanupException("Evidence process tree did not terminate within the bounded cleanup timeout")
            }
            result = EvidenceProcessResult(
                completed = completed,
                exitCode = if (completed) started.exitValue() else null,
                output = readOutput(output),
            )
        } catch (error: Throwable) {
            if (error is InterruptedException) interruptState.observed = true
            primaryFailure = error
        } finally {
            if (Thread.interrupted()) interruptState.observed = true
            val cleanupFailure = finishEvidenceProcess(
                process = process,
                capturedTree = capturedTree,
                output = output,
                interruptState = interruptState,
                deleteOutput = deleteOutput,
                deferOutputDeletion = deferOutputDeletion,
            )
            if (cleanupFailure != null) {
                if (primaryFailure == null) {
                    primaryFailure = cleanupFailure
                } else {
                    primaryFailure.addSuppressed(cleanupFailure)
                }
            }
            if (interruptState.observed) Thread.currentThread().interrupt()
        }
        primaryFailure?.let { throw it }
        return checkNotNull(result) { "Evidence process produced neither a result nor a failure" }
    }

    private fun finishEvidenceProcess(
        process: Process?,
        capturedTree: MutableMap<Long, ProcessHandle>,
        output: Path,
        interruptState: InterruptState,
        deleteOutput: (Path, InterruptState) -> Boolean,
        deferOutputDeletion: (Path, Map<Long, ProcessHandle>, InterruptState) -> DeferredDeletionOutcome,
    ): EvidenceProcessCleanupException? {
        val cleanupSucceeded = try {
            process?.let { started -> terminateAndReapProcessTree(started, capturedTree, interruptState) } ?: true
        } catch (error: Throwable) {
            return cleanupException(
                "Evidence process cleanup threw; output retained at $output",
                error,
                interruptState,
            )
        }
        if (cleanupSucceeded) {
            val outputDeleted = try {
                deleteOutput(output, interruptState)
            } catch (error: Throwable) {
                return cleanupException(
                    "Evidence output deletion threw; output retained at $output",
                    error,
                    interruptState,
                )
            }
            if (outputDeleted) return null
        }

        val deferredOutcome = try {
            deferOutputDeletion(output, capturedTree, interruptState)
        } catch (error: Throwable) {
            return cleanupException(
                "Bounded deferred output cleanup threw; output retained at $output",
                error,
                interruptState,
            )
        }
        if (cleanupSucceeded && deferredOutcome == DeferredDeletionOutcome.DELETED) return null
        return EvidenceProcessCleanupException(
            "Evidence process cleanup did not complete; deferred deletion outcome=$deferredOutcome; output=$output",
            deferredDeletionOutcome = deferredOutcome,
        )
    }

    private fun cleanupException(
        message: String,
        error: Throwable,
        interruptState: InterruptState,
    ): EvidenceProcessCleanupException {
        if (error is InterruptedException) interruptState.observed = true
        return EvidenceProcessCleanupException(message, cause = error)
    }

    private fun waitForEvidenceProcess(
        process: Process,
        timeout: Long,
        unit: TimeUnit,
        capturedTree: MutableMap<Long, ProcessHandle>,
    ): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (true) {
            captureProcessTree(process, capturedTree)
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return !process.isAlive
            val waitSlice = minOf(remaining, TimeUnit.MILLISECONDS.toNanos(processCapturePollMillis))
            if (process.waitFor(waitSlice, TimeUnit.NANOSECONDS)) {
                captureProcessTree(process, capturedTree)
                return true
            }
        }
    }

    private fun captureProcessTree(process: Process, capturedTree: MutableMap<Long, ProcessHandle>) {
        val root = process.toHandle()
        capturedTree.putIfAbsent(root.pid(), root)
        root.descendants().forEach { descendant ->
            capturedTree.putIfAbsent(descendant.pid(), descendant)
        }
    }

    private fun terminateAndReapProcessTree(
        process: Process,
        capturedTree: MutableMap<Long, ProcessHandle>,
        interruptState: InterruptState,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(processCleanupTimeoutSeconds)
        captureProcessTree(process, capturedTree)
        val root = process.toHandle()
        if (root.isAlive) root.destroyForcibly()
        captureProcessTree(process, capturedTree)
        val processes = capturedTree.values.toList()
        processes.filterNot { it.pid() == root.pid() }.asReversed().forEach { handle ->
            if (handle.isAlive) handle.destroyForcibly()
        }
        var allExited = true
        processes.forEach { handle ->
            if (!waitForExit(handle, deadline, interruptState)) allExited = false
        }
        val rootReaped = waitForReap(process, deadline, interruptState)
        return allExited && rootReaped
    }

    private fun waitForReap(process: Process, deadline: Long, interruptState: InterruptState): Boolean {
        while (process.isAlive) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            try {
                if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) return true
            } catch (_: InterruptedException) {
                interruptState.observed = true
            }
        }
        return true
    }

    private fun waitForExit(process: ProcessHandle, deadline: Long, interruptState: InterruptState): Boolean {
        while (process.isAlive) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            try {
                process.onExit().get(remaining, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                interruptState.observed = true
            } catch (_: TimeoutException) {
                return false
            } catch (_: ExecutionException) {
                return !process.isAlive
            }
        }
        return true
    }

    private fun deleteEvidenceOutput(output: Path, interruptState: InterruptState): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(processCleanupTimeoutSeconds)
        while (true) {
            try {
                Files.deleteIfExists(output)
                return true
            } catch (_: IOException) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                try {
                    Thread.sleep(
                        minOf(
                            processOutputDeleteRetryMillis,
                            TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1),
                        ),
                    )
                } catch (_: InterruptedException) {
                    interruptState.observed = true
                }
            }
        }
    }

    private fun scheduleDeferredOutputDeletion(
        output: Path,
        capturedTree: Map<Long, ProcessHandle>,
        interruptState: InterruptState,
        timeout: Long = processCleanupTimeoutSeconds,
        unit: TimeUnit = TimeUnit.SECONDS,
        deleteOutput: (Path, InterruptState) -> Boolean = ::deleteEvidenceOutput,
    ): DeferredDeletionOutcome {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        var allExited = true
        capturedTree.values.forEach { process ->
            if (!waitForExit(process, deadline, interruptState)) allExited = false
        }
        if (!allExited) return DeferredDeletionOutcome.RETAINED_LIVE_PROCESS
        return if (deleteOutput(output, interruptState)) {
            DeferredDeletionOutcome.DELETED
        } else {
            DeferredDeletionOutcome.DELETE_FAILED
        }
    }

    private fun successfulEvidenceCommand(): List<String> =
        if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd", "/c", "exit", "0")
        } else {
            listOf("sh", "-c", "exit 0")
        }

    private fun longRunningEvidenceCommand(): List<String> =
        if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 30")
        } else {
            listOf("sh", "-c", "sleep 30")
        }

    private fun timeoutWrapperCommand(pidFile: Path): List<String> =
        if (System.getProperty("os.name").startsWith("Windows")) {
            listOf(
                "powershell",
                "-NoProfile",
                "-Command",
                "${'$'}child = Start-Process powershell -ArgumentList '-NoProfile', '-Command', 'Start-Sleep -Seconds 30' -PassThru; Set-Content -NoNewline -LiteralPath '${pidFile.toAbsolutePath()}' -Value (${'$'}PID.ToString() + ',' + ${'$'}child.Id.ToString()); Start-Sleep -Seconds 30",
            )
        } else {
            listOf(
                "sh",
                "-c",
                "(sleep 30) & child=\$!; printf '%s,%s\\n' \"\$\$\" \"\$child\" > '${pidFile.toAbsolutePath()}'; sleep 30",
            )
        }

    private fun isProcessAlive(pid: Long): Boolean = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)

    private fun terminateForTest(pid: Long) {
        ProcessHandle.of(pid).ifPresent { process ->
            process.destroyForcibly()
            process.onExit().get(2, TimeUnit.SECONDS)
        }
    }

    private fun awaitPidFile(pidFile: Path): List<Long> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(processSetupTimeoutSeconds)
        while (System.nanoTime() < deadline) {
            val pids = readRecordedPids(pidFile)
            if (pids.size == 2) return pids
            Thread.sleep(10)
        }
        throw AssertionError("The wrapper did not record both PIDs within the bounded setup time")
    }

    private fun readRecordedPids(pidFile: Path): List<Long> =
        if (Files.exists(pidFile)) {
            runCatching { Files.readString(pidFile).trim().split(',').mapNotNull(String::toLongOrNull) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
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

    private fun validateWorkflow(content: String): List<String> =
        validateCanonical(content, canonicalWorkflow, "CI workflow must use the one supported fail-closed foundation structure")

    private fun validateDetekt(content: String): List<String> =
        validateCanonical(content, canonicalDetektConfig, "Detekt configuration must keep maxIssues and validation under their required sections")

    private fun validateCanonical(content: String, canonical: String, message: String): List<String> {
        val normalized = content.replace("\r\n", "\n").trimEnd() + "\n"
        return if (normalized == canonical) emptyList() else listOf(message)
    }

    private fun requireExactlyOne(content: String, declaration: Regex, message: String, failures: MutableList<String>) {
        if (declaration.findAll(content).count() != 1) failures += message
    }

    private fun assertWorkflowRejected(content: String) = assertTrue(validateWorkflow(content).isNotEmpty())

    private fun assertDetektRejected(content: String) = assertTrue(validateDetekt(content).isNotEmpty())

    private fun projectRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { it.resolve(".git").exists() }

    private data class AdrEvidence(
        val path: String,
        val sections: List<String>,
        val decisionTerms: List<String>,
    )

    private data class EvidenceProcessResult(
        val completed: Boolean,
        val exitCode: Int?,
        val output: String,
    )

    private class EvidenceProcessCleanupException(
        message: String,
        val deferredDeletionOutcome: DeferredDeletionOutcome? = null,
        cause: Throwable? = null,
    ) :
        IllegalStateException(message, cause)

    private class InterruptState(var observed: Boolean = false)

    private enum class DeferredDeletionOutcome {
        DELETED,
        RETAINED_LIVE_PROCESS,
        DELETE_FAILED,
    }

    private companion object {
        const val architectureSource = "docs/superpowers/specs/2026-08-30-system-architecture-v0.1.md"
        const val processCleanupTimeoutSeconds = 5L
        const val processSetupTimeoutSeconds = 15L
        const val processCapturePollMillis = 10L
        const val processOutputDeleteRetryMillis = 10L
        val canonicalWorkflow = """
            name: Android verification

            on:
              workflow_dispatch:
              pull_request:
              push:
                branches:
                  - main

            permissions:
              contents: read

            jobs:
              foundation:
                runs-on: ubuntu-24.04
                timeout-minutes: 30
                steps:
                  - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1
                  - uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961
                    with:
                      distribution: temurin
                      java-version: '17'
                      cache: gradle
                      check-latest: false
                  - name: Set up Android SDK
                    uses: android-actions/setup-android@40fd30fb8d7440372e1316f5d1809ec01dcd3699
                  - name: Install Android SDK packages
                    run: sdkmanager --install "platforms;android-37.0" "build-tools;36.0.0" --channel=3
                  - name: Verify foundation
                    run: ./gradlew test verifyArchitecture :app:assembleDebug lint
        """.trimIndent() + "\n"
        val canonicalDetektConfig = """
            build:
              maxIssues: 0

            config:
              validation: true
        """.trimIndent() + "\n"
        val foundationAdrs = listOf(
            AdrEvidence("docs/adr/ADR-001-module-boundaries.md", listOf("Section 5", "Section 30"), listOf("modular monolith", "Ports/Adapters", "module dependency boundaries")),
            AdrEvidence("docs/adr/ADR-002-single-writer-runtime.md", listOf("Section 7", "Section 9", "Section 10", "Section 30"), listOf("RunCoordinator", "sole Run-state writer", "long I/O", "version/attempt validation")),
            AdrEvidence("docs/adr/ADR-003-persistence-event-ledger.md", listOf("Section 12", "Section 13", "Section 30"), listOf("authoritative relational facts", "append-only Runtime Event Ledger", "encrypted content/evidence", "rebuildable projections")),
            AdrEvidence("docs/adr/ADR-004-projection-boundary.md", listOf("Section 12.4", "Section 13.4", "Section 14", "Section 30"), listOf("global event ordinal", "per-Run sequence", "expected version", "committed facts", "never become authority")),
        )
    }
}
