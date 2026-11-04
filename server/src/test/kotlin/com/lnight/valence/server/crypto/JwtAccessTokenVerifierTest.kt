package com.lnight.valence.server.crypto

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JwtAccessTokenVerifierTest {
    private val mailboxId = "mbx_g4r2k7x5qmwv6bnzdcs3n4pe5a"
    private val key = ByteArray(32) { it.toByte() }
    private val issuedAt = 1_760_000_001L
    private val expiresAt = 1_760_003_601L
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    private fun clockAt(epochSecond: Long) = Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC)

    private fun verifier(
        atSecond: Long = issuedAt + 100,
        signingKey: ByteArray = key,
    ) = JwtAccessTokenVerifier(clockAt(atSecond), signingKey)

    private fun issued(): String = JwtAccessTokenIssuer(clockAt(issuedAt), key).issue(mailboxId).value

    private fun encode(text: String) = encoder.encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun claims(
        subject: String = mailboxId,
        expiry: Long = expiresAt,
    ) = """{"sub":"$subject","iat":$issuedAt,"exp":$expiry}"""

    private fun signed(
        claims: String,
        header: String = """{"alg":"HS256","typ":"JWT"}""",
        signingKey: ByteArray = key,
    ): String {
        val input = "${encode(header)}.${encode(claims)}"
        val mac = Mac.getInstance("HmacSHA256").also { it.init(SecretKeySpec(signingKey, "HmacSHA256")) }
        return "$input.${encoder.encodeToString(mac.doFinal(input.toByteArray(Charsets.US_ASCII)))}"
    }

    @Test
    fun `a token from the issuer is accepted`() {
        assertEquals(mailboxId, verifier().verify(issued()))
    }

    @Test
    fun `a token is valid until its expiry`() {
        assertEquals(mailboxId, verifier(atSecond = expiresAt - 1).verify(issued()))
        assertNull(verifier(atSecond = expiresAt).verify(issued()))
    }

    @Test
    fun `a modified payload is rejected`() {
        val parts = issued().split('.')
        val forged = "${parts[0]}.${encode(claims(subject = "mbx_bbbbbbbbbbbbbbbbbbbbbbbbbb"))}.${parts[2]}"

        assertNull(verifier().verify(forged))
    }

    @Test
    fun `a modified signature is rejected`() {
        val parts = issued().split('.')
        val signature = parts[2].replaceRange(0, 1, if (parts[2].startsWith("A")) "B" else "A")

        assertNull(verifier().verify("${parts[0]}.${parts[1]}.$signature"))
    }

    @Test
    fun `a token signed with another key is rejected`() {
        assertNull(verifier().verify(signed(claims(), signingKey = ByteArray(32) { 1 })))
    }

    @Test
    fun `other algorithms are rejected`() {
        val none = "${encode("""{"alg":"none"}""")}.${encode(claims())}."

        assertNull(verifier().verify(none))
        assertNull(verifier().verify(signed(claims(), header = """{"alg":"HS512","typ":"JWT"}""")))
    }

    @Test
    fun `claims without a valid subject or expiry are rejected`() {
        assertNull(verifier().verify(signed("""{"iat":1}""")))
        assertNull(verifier().verify(signed("""{"sub":"$mailboxId"}""")))
        assertNull(verifier().verify(signed(claims(subject = "admin"))))
    }

    @Test
    fun `malformed tokens are rejected`() {
        listOf("", "a.b", "a.b.c.d", "!!!.!!!.!!!", "....").forEach { assertNull(verifier().verify(it)) }
    }

    @Test
    fun `a short key is rejected`() {
        assertFailsWith<IllegalArgumentException> { JwtAccessTokenVerifier(clockAt(issuedAt), ByteArray(31)) }
    }
}
