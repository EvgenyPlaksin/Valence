package com.lnight.valence.server.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JwtAccessTokenIssuerTest {
    private val mailboxId = "mbx_g4r2k7x5qmwv6bnzdcs3n4pe5a"
    private val key = ByteArray(32) { it.toByte() }
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_760_000_001L), ZoneOffset.UTC)
    private val token = JwtAccessTokenIssuer(clock, key).issue(mailboxId)
    private val parts = token.value.split('.')
    private val decoder = Base64.getUrlDecoder()

    private fun signature(signingKey: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey, "HmacSHA256"))
        return mac.doFinal("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
    }

    private fun part(index: Int) = Json.parseToJsonElement(String(decoder.decode(parts[index]))).jsonObject

    @Test
    fun `a token has three parts`() {
        assertEquals(3, parts.size)
    }

    @Test
    fun `the header names HS256`() {
        assertEquals("HS256", part(0).getValue("alg").jsonPrimitive.content)
        assertEquals("JWT", part(0).getValue("typ").jsonPrimitive.content)
    }

    @Test
    fun `the claims follow the specification`() {
        assertEquals(mailboxId, part(1).getValue("sub").jsonPrimitive.content)
        assertEquals("1760000001", part(1).getValue("iat").jsonPrimitive.content)
        assertEquals("1760003601", part(1).getValue("exp").jsonPrimitive.content)
    }

    @Test
    fun `the reported expiry is in milliseconds`() {
        assertEquals(1_760_003_601_000L, token.expiresAtMillis)
    }

    @Test
    fun `the signature verifies under the key and not under another`() {
        assertContentEquals(signature(key), decoder.decode(parts[2]))
        assertEquals(false, signature(ByteArray(32) { 1 }).contentEquals(decoder.decode(parts[2])))
    }

    @Test
    fun `a short key is rejected`() {
        assertFailsWith<IllegalArgumentException> { JwtAccessTokenIssuer(clock, ByteArray(31)) }
    }
}
