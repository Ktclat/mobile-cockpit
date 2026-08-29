package dev.cockpit.domain.time

@JvmInline
value class InstantValue(val epochMilliseconds: Long)

interface AppClock {
    fun now(): InstantValue
}
