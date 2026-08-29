package dev.cockpit.runtime

@JvmInline
value class DispatcherLane(val value: String)

fun interface RuntimeDispatcher {
    fun dispatch(block: () -> Unit)
}

interface DispatcherProvider {
    fun dispatcherFor(lane: DispatcherLane): RuntimeDispatcher
}
