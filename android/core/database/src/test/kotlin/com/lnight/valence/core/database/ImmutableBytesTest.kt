package com.lnight.valence.core.database

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImmutableBytesTest {
    @Test
    fun encapsulatesByteArrayImmutably() {
        val original = byteArrayOf(1, 2, 3)
        val immutable = ImmutableBytes(original)

        original[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), immutable.copyBytes())

        val retrieved = immutable.copyBytes()
        retrieved[0] = 8
        assertArrayEquals(byteArrayOf(1, 2, 3), immutable.copyBytes())
    }

    @Test
    fun equalityAndHashCodeWork() {
        val b1 = ImmutableBytes(byteArrayOf(1, 2, 3))
        val b2 = ImmutableBytes(byteArrayOf(1, 2, 3))
        val b3 = ImmutableBytes(byteArrayOf(1, 2, 4))

        assertEquals(b1, b2)
        assertEquals(b1.hashCode(), b2.hashCode())
        assertNotEquals(b1, b3)
    }
}
