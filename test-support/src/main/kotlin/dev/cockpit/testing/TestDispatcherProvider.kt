package dev.cockpit.testing

import dev.cockpit.runtime.DispatcherLane
import dev.cockpit.runtime.DispatcherProvider
import dev.cockpit.runtime.RuntimeDispatcher

class TestDispatcherProvider(private val configuredLane: DispatcherLane) : DispatcherProvider {
    private val queuedWork = ArrayDeque<QueuedWork>()

    override fun dispatcherFor(lane: DispatcherLane): RuntimeDispatcher {
        check(lane == configuredLane) {
            "No test dispatcher is configured for lane ${lane.value}."
        }
        return RuntimeDispatcher { block ->
            queuedWork.addLast(QueuedWork(lane, block))
        }
    }

    fun queuedLanes(): List<DispatcherLane> = queuedWork.map(QueuedWork::lane)

    fun runNext(): Boolean {
        val work = queuedWork.removeFirstOrNull() ?: return false
        work.block()
        return true
    }

    private data class QueuedWork(val lane: DispatcherLane, val block: () -> Unit)
}
