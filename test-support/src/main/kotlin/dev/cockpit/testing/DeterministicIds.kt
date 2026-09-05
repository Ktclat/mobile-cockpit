package dev.cockpit.testing

import dev.cockpit.domain.ids.IdGenerator

class DeterministicIds(vararg ids: String) : IdGenerator {
    private val values = ids.toList()
    private var nextIndex = 0

    override fun nextId(): String = checkNotNull(values.getOrNull(nextIndex++)) {
        "No deterministic ID was configured for index ${nextIndex - 1}."
    }
}
