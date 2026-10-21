package com.lnight.valence.server.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CryptoAdaptersTest {
    @Test
    fun `a short key is rejected`() {
        assertFailsWith<IllegalArgumentException> { HmacChallengeAuthenticator(ByteArray(31)) }
    }

    @Test
    fun `signing is deterministic and depends on the key`() {
        val data = "payload".toByteArray(Charsets.UTF_8)
        val first = HmacChallengeAuthenticator(ByteArray(32) { 1 })
        val second = HmacChallengeAuthenticator(ByteArray(32) { 2 })

        assertContentEquals(first.sign(data), first.sign(data))
        assertNotEquals(first.sign(data).toList(), second.sign(data).toList())
    }

    @Test
    fun `random bytes have the requested size and differ between calls`() {
        val random = SecureRandomBytes()

        assertEquals(16, random.next(16).size)
        assertNotEquals(random.next(16).toList(), random.next(16).toList())
    }
}
