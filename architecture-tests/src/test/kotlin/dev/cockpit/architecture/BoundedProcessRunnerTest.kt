package dev.cockpit.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BoundedProcessRunnerTest {
    @Test
    fun timeoutReapsWrapperAndChildBeforeDeletingOutput() {
        val pidFile = Files.createTempFile("cockpit-bounded-process-timeout-", ".pids")
        val output = Files.createTempFile("cockpit-bounded-process-timeout-", ".txt")
        try {
            val result = BoundedProcessRunner.run(
                command = wrapperAndChildCommand(pidFile),
                workingDirectory = projectRoot(),
                timeout = 1,
                unit = TimeUnit.SECONDS,
                output = output,
            )

            val pids = awaitPidFile(pidFile)
            assertFalse(result.completed, "The long-lived wrapper must take the timeout path")
            assertTrue(pids.none(::isProcessAlive), "Timeout cleanup must reap the wrapper and its child")
            assertFalse(Files.exists(output), "Output must be deleted after the captured process tree exits")
        } finally {
            readRecordedPids(pidFile).forEach(::terminateForTest)
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun interruptionReapsWrapperAndChildAndDeletesOutput() {
        val pidFile = Files.createTempFile("cockpit-bounded-process-interrupt-", ".pids")
        val output = Files.createTempFile("cockpit-bounded-process-interrupt-", ".txt")
        val temporaryDirectory = Files.createTempDirectory("cockpit-bounded-process-interrupt-")
        Files.writeString(temporaryDirectory.resolve("owned.txt"), "owned")
        try {
            assertThrows(InterruptedException::class.java) {
                BoundedProcessRunner.run(
                    command = wrapperAndChildCommand(pidFile),
                    workingDirectory = projectRoot(),
                    timeout = 30,
                    unit = TimeUnit.SECONDS,
                    output = output,
                    ownedTemporaryDirectory = temporaryDirectory,
                    afterStart = {
                        awaitPidFile(pidFile)
                        Thread.currentThread().interrupt()
                    },
                )
            }

            assertTrue(Thread.interrupted(), "Process cleanup must restore the caller's interrupt status")
            assertTrue(awaitPidFile(pidFile).none(::isProcessAlive), "Interruption cleanup must reap the wrapper and its child")
            assertFalse(Files.exists(output), "Interruption cleanup must delete its known output file")
            assertFalse(Files.exists(temporaryDirectory), "Interruption cleanup must delete its owned temporary directory")
        } finally {
            Thread.interrupted()
            readRecordedPids(pidFile).forEach(::terminateForTest)
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(output)
            deleteTreeForTest(temporaryDirectory)
        }
    }

    @Test
    fun preparationFailurePreservesPrimaryAndDeletesOwnedTemporaryDirectory() {
        val temporaryDirectory = Files.createTempDirectory("cockpit-bounded-process-prepare-")
        val output = Files.createTempFile("cockpit-bounded-process-prepare-", ".txt")
        val primary = IllegalStateException("primary preparation failure")
        try {
            val thrown = assertThrows(IllegalStateException::class.java) {
                BoundedProcessRunner.run(
                    command = successfulCommand(),
                    workingDirectory = projectRoot(),
                    timeout = 5,
                    unit = TimeUnit.SECONDS,
                    output = output,
                    ownedTemporaryDirectory = temporaryDirectory,
                    prepare = {
                        Files.writeString(temporaryDirectory.resolve("prepared.txt"), "prepared")
                        throw primary
                    },
                )
            }

            assertTrue(thrown === primary, "Owned-resource cleanup must not replace a preparation failure")
            assertFalse(Files.exists(output), "Preparation failure must delete its known output file")
            assertFalse(Files.exists(temporaryDirectory), "Preparation failure must delete its owned temporary directory")
        } finally {
            Files.deleteIfExists(output)
            deleteTreeForTest(temporaryDirectory)
        }
    }

    @Test
    fun readsOutputOnlyAfterEveryCapturedProcessExits() {
        val pidFile = Files.createTempFile("cockpit-bounded-process-read-order-", ".pids")
        val output = Files.createTempFile("cockpit-bounded-process-read-order-", ".txt")
        var pids = emptyList<Long>()
        try {
            BoundedProcessRunner.run(
                command = wrapperAndChildCommand(pidFile),
                workingDirectory = projectRoot(),
                timeout = 30,
                unit = TimeUnit.SECONDS,
                output = output,
                afterStart = { wrapper ->
                    pids = awaitPidFile(pidFile)
                    wrapper.destroyForcibly()
                },
                readOutput = { path ->
                    assertTrue(pids.none(::isProcessAlive), "Output must not be read while a captured process is alive")
                    Files.readString(path)
                },
            )
        } finally {
            pids.forEach(::terminateForTest)
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun cleanupFailureIsSuppressedOntoPrimaryFailure() {
        val output = Files.createTempFile("cockpit-bounded-process-primary-", ".txt")
        val primary = IllegalStateException("primary process failure")
        try {
            val thrown = assertThrows(IllegalStateException::class.java) {
                BoundedProcessRunner.run(
                    command = successfulCommand(),
                    workingDirectory = projectRoot(),
                    timeout = 5,
                    unit = TimeUnit.SECONDS,
                    output = output,
                    afterStart = { throw primary },
                    deleteOutput = { _, _ -> false },
                    deferredOutputDeletion = { _, _, _ -> BoundedProcessDeletionOutcome.DELETE_FAILED },
                )
            }

            assertTrue(thrown === primary, "Cleanup failure must not replace the primary process failure")
            assertTrue(
                thrown.suppressed.singleOrNull() is BoundedProcessCleanupException,
                "Cleanup failure must be attached to the primary failure as suppressed evidence",
            )
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun projectRoot(): Path =
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve(".git")) }

    private fun successfulCommand(): List<String> =
        if (isWindows) listOf("cmd", "/c", "exit", "0") else listOf("sh", "-c", "exit 0")

    private fun wrapperAndChildCommand(pidFile: Path): List<String> =
        if (isWindows) {
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

    private fun awaitPidFile(pidFile: Path): List<Long> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val pids = readRecordedPids(pidFile)
            if (pids.size == 2) return pids
            Thread.sleep(10)
        }
        throw AssertionError("The wrapper did not record both PIDs within the bounded setup time")
    }

    private fun readRecordedPids(pidFile: Path): List<Long> =
        runCatching { Files.readString(pidFile).trim().split(',').mapNotNull(String::toLongOrNull) }
            .getOrDefault(emptyList())

    private fun isProcessAlive(pid: Long): Boolean = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)

    private fun terminateForTest(pid: Long) {
        ProcessHandle.of(pid).ifPresent { process ->
            if (process.isAlive) process.destroyForcibly()
            process.onExit().get(2, TimeUnit.SECONDS)
        }
    }

    private fun deleteTreeForTest(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        val isWindows = System.getProperty("os.name").startsWith("Windows")
    }
}
