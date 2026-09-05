package dev.cockpit.testing

import dev.cockpit.domain.time.AppClock
import dev.cockpit.domain.time.InstantValue

class FakeClock(initial: InstantValue) : AppClock {
    private val current = initial

    override fun now(): InstantValue = current
}
