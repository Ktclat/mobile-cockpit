package dev.cockpit.runtime

@JvmInline
value class DispatcherLane(val value: String)

@JvmInline
value class DispatcherIdentity(val value: String)

interface DispatcherProvider {
    fun dispatcherFor(lane: DispatcherLane): DispatcherIdentity
}
