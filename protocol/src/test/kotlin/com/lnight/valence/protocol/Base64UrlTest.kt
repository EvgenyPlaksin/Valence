package com.lnight.valence.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Base64UrlTest {
    @Test
    fun `encoding uses the url safe alphabet without padding`() {
        assertEquals("-_8", Base64Url.encode(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
    }

    @Test
    fun `decoding reverses encoding`() {
        val bytes = ByteArray(32) { it.toByte() }

        assertContentEquals(bytes, Base64Url.decode(Base64Url.encode(bytes)))
    }

    @Test
    fun `decoding rejects characters outside the alphabet`() {
        assertFailsWith<IllegalArgumentException> { Base64Url.decode("a+b/") }
    }
}
