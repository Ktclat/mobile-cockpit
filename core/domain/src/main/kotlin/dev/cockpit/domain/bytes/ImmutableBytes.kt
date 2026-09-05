package dev.cockpit.domain.bytes

class ImmutableBytes(bytes: ByteArray) {
    private val bytes: ByteArray = bytes.copyOf()

    val size: Int
        get() = bytes.size

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ImmutableBytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ImmutableBytes(size=$size)"

    companion object {
        fun copyOf(bytes: ByteArray): ImmutableBytes = ImmutableBytes(bytes)
    }
}
