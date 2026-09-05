package dev.cockpit.architecture

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal object BoundedProcessRunner {
    fun run(
        command: List<String>,
        workingDirectory: Path,
        timeout: Long,
        unit: TimeUnit,
        output: Path = Files.createTempFile("cockpit-bounded-process-", ".txt"),
        ownedTemporaryDirectory: Path? = null,
        prepare: () -> Unit = {},
        afterStart: (Process) -> Unit = {},
        readOutput: (Path) -> String = Files::readString,
        deleteOutput: (Path, BoundedProcessInterruptState) -> Boolean = ::deleteOutput,
        deferredOutputDeletion: (
            Path,
            Map<Long, ProcessHandle>,
            BoundedProcessInterruptState,
        ) -> BoundedProcessDeletionOutcome = ::deleteOutputAfterCapturedTreeExits,
    ): BoundedProcessResult {
        var process: Process? = null
        val capturedTree = linkedMapOf<Long, ProcessHandle>()
        val interruptState = BoundedProcessInterruptState()
        var result: BoundedProcessResult? = null
        var primaryFailure: Throwable? = null
        try {
            prepare()
            val started = ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start()
            process = started
            captureProcessTree(started, capturedTree)
            afterStart(started)
            captureProcessTree(started, capturedTree)
            val completed = waitForProcess(started, timeout, unit, capturedTree)
            if (!terminateAndReapProcessTree(started, capturedTree, interruptState)) {
                throw BoundedProcessCleanupException(
                    "Bounded process tree did not terminate within the cleanup timeout",
                )
            }
            result = BoundedProcessResult(completed, if (completed) started.exitValue() else null, readOutput(output))
        } catch (error: Throwable) {
            if (error is InterruptedException) interruptState.observed = true
            primaryFailure = error
        } finally {
            if (Thread.interrupted()) interruptState.observed = true
            val cleanupFailure = finishProcess(
                process = process,
                capturedTree = capturedTree,
                output = output,
                interruptState = interruptState,
                deleteOutput = deleteOutput,
                deferredOutputDeletion = deferredOutputDeletion,
            )
            if (cleanupFailure != null) {
                if (primaryFailure == null) {
                    primaryFailure = cleanupFailure
                } else {
                    primaryFailure.addSuppressed(cleanupFailure)
                }
            }
            val ownedDirectoryCleanupFailure = cleanupOwnedTemporaryDirectory(
                ownedTemporaryDirectory,
                interruptState,
            )
            if (ownedDirectoryCleanupFailure != null) {
                if (primaryFailure == null) {
                    primaryFailure = ownedDirectoryCleanupFailure
                } else {
                    primaryFailure.addSuppressed(ownedDirectoryCleanupFailure)
                }
            }
            if (interruptState.observed) Thread.currentThread().interrupt()
        }
        primaryFailure?.let { throw it }
        return checkNotNull(result) { "Bounded process produced neither a result nor a failure" }
    }

    private fun finishProcess(
        process: Process?,
        capturedTree: MutableMap<Long, ProcessHandle>,
        output: Path,
        interruptState: BoundedProcessInterruptState,
        deleteOutput: (Path, BoundedProcessInterruptState) -> Boolean,
        deferredOutputDeletion: (
            Path,
            Map<Long, ProcessHandle>,
            BoundedProcessInterruptState,
        ) -> BoundedProcessDeletionOutcome,
    ): BoundedProcessCleanupException? {
        val cleanupSucceeded = try {
            process?.let { terminateAndReapProcessTree(it, capturedTree, interruptState) } ?: true
        } catch (error: Throwable) {
            return cleanupException("Bounded process cleanup threw; output retained at $output", error, interruptState)
        }
        if (cleanupSucceeded) {
            val outputDeleted = try {
                deleteOutput(output, interruptState)
            } catch (error: Throwable) {
                return cleanupException("Bounded output deletion threw; output retained at $output", error, interruptState)
            }
            if (outputDeleted) return null
        }

        val deferredOutcome = try {
            deferredOutputDeletion(output, capturedTree, interruptState)
        } catch (error: Throwable) {
            return cleanupException("Bounded deferred output cleanup threw; output retained at $output", error, interruptState)
        }
        if (cleanupSucceeded && deferredOutcome == BoundedProcessDeletionOutcome.DELETED) return null
        return BoundedProcessCleanupException(
            "Bounded process cleanup did not complete; deferred deletion outcome=$deferredOutcome; output=$output",
            deferredDeletionOutcome = deferredOutcome,
        )
    }

    private fun cleanupException(
        message: String,
        error: Throwable,
        interruptState: BoundedProcessInterruptState,
    ): BoundedProcessCleanupException {
        if (error is InterruptedException) interruptState.observed = true
        return BoundedProcessCleanupException(message, cause = error)
    }

    private fun waitForProcess(
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
            val waitSlice = minOf(remaining, TimeUnit.MILLISECONDS.toNanos(PROCESS_CAPTURE_POLL_MILLIS))
            if (process.waitFor(waitSlice, TimeUnit.NANOSECONDS)) {
                captureProcessTree(process, capturedTree)
                return true
            }
        }
    }

    private fun captureProcessTree(process: Process, capturedTree: MutableMap<Long, ProcessHandle>) {
        val root = process.toHandle()
        capturedTree.putIfAbsent(root.pid(), root)
        root.descendants().forEach { descendant -> capturedTree.putIfAbsent(descendant.pid(), descendant) }
    }

    private fun terminateAndReapProcessTree(
        process: Process,
        capturedTree: MutableMap<Long, ProcessHandle>,
        interruptState: BoundedProcessInterruptState,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_CLEANUP_TIMEOUT_SECONDS)
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

    private fun waitForReap(
        process: Process,
        deadline: Long,
        interruptState: BoundedProcessInterruptState,
    ): Boolean {
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

    private fun waitForExit(
        process: ProcessHandle,
        deadline: Long,
        interruptState: BoundedProcessInterruptState,
    ): Boolean {
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

    private fun deleteOutput(output: Path, interruptState: BoundedProcessInterruptState): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_CLEANUP_TIMEOUT_SECONDS)
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
                            OUTPUT_DELETE_RETRY_MILLIS,
                            TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1),
                        ),
                    )
                } catch (_: InterruptedException) {
                    interruptState.observed = true
                }
            }
        }
    }

    private fun deleteOutputAfterCapturedTreeExits(
        output: Path,
        capturedTree: Map<Long, ProcessHandle>,
        interruptState: BoundedProcessInterruptState,
    ): BoundedProcessDeletionOutcome {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_CLEANUP_TIMEOUT_SECONDS)
        var allExited = true
        capturedTree.values.forEach { process ->
            if (!waitForExit(process, deadline, interruptState)) allExited = false
        }
        if (!allExited) return BoundedProcessDeletionOutcome.RETAINED_LIVE_PROCESS
        return if (deleteOutput(output, interruptState)) {
            BoundedProcessDeletionOutcome.DELETED
        } else {
            BoundedProcessDeletionOutcome.DELETE_FAILED
        }
    }

    private fun cleanupOwnedTemporaryDirectory(
        root: Path?,
        interruptState: BoundedProcessInterruptState,
    ): BoundedProcessCleanupException? {
        if (root == null) return null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROCESS_CLEANUP_TIMEOUT_SECONDS)
        var lastFailure: Throwable? = null
        while (true) {
            try {
                if (Files.exists(root)) {
                    Files.walk(root).use { paths ->
                        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                    }
                }
                return null
            } catch (error: IOException) {
                lastFailure = error
            } catch (error: UncheckedIOException) {
                lastFailure = error
            } catch (error: Throwable) {
                return cleanupException(
                    "Owned temporary-directory cleanup threw; directory retained at $root",
                    error,
                    interruptState,
                )
            }

            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                return BoundedProcessCleanupException(
                    "Timed out deleting owned temporary directory: $root",
                    cause = lastFailure,
                )
            }
            try {
                Thread.sleep(
                    minOf(
                        OUTPUT_DELETE_RETRY_MILLIS,
                        TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1),
                    ),
                )
            } catch (_: InterruptedException) {
                interruptState.observed = true
            }
        }
    }

    private const val PROCESS_CLEANUP_TIMEOUT_SECONDS = 5L
    private const val PROCESS_CAPTURE_POLL_MILLIS = 10L
    private const val OUTPUT_DELETE_RETRY_MILLIS = 10L
}

internal data class BoundedProcessResult(
    val completed: Boolean,
    val exitCode: Int?,
    val output: String,
)

internal class BoundedProcessCleanupException(
    message: String,
    val deferredDeletionOutcome: BoundedProcessDeletionOutcome? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class BoundedProcessInterruptState(var observed: Boolean = false)

internal enum class BoundedProcessDeletionOutcome {
    DELETED,
    RETAINED_LIVE_PROCESS,
    DELETE_FAILED,
}
