package com.lnight.valence.core.database

class ImmutableBytes(
    bytes: ByteArray,
) {
    private val value = bytes.copyOf()

    fun copyBytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is ImmutableBytes && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "ImmutableBytes(size=${value.size})"
}
