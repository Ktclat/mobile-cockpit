package dev.cockpit.domain.bytes

class ImmutableBytes private constructor(private val bytes: ByteArray) {
    val size: Int
        get() = bytes.size

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ImmutableBytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ImmutableBytes(size=$size)"

    companion object {
        fun copyOf(bytes: ByteArray): ImmutableBytes = ImmutableBytes(bytes.copyOf())
    }
}
