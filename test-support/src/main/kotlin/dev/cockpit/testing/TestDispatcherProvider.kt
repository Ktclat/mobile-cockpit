package dev.cockpit.testing

import dev.cockpit.runtime.DispatcherIdentity
import dev.cockpit.runtime.DispatcherLane
import dev.cockpit.runtime.DispatcherProvider

class TestDispatcherProvider(vararg bindings: Pair<DispatcherLane, DispatcherIdentity>) : DispatcherProvider {
    private val identitiesByLane = bindings.toMap()

    override fun dispatcherFor(lane: DispatcherLane): DispatcherIdentity = checkNotNull(identitiesByLane[lane]) {
        "No test dispatcher is configured for lane ${lane.value}."
    }
}
