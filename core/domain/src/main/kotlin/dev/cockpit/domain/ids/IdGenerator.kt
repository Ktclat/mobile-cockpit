package dev.cockpit.domain.ids

interface IdGenerator {
    fun nextId(): String
}
