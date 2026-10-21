package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.GoldenFixtures
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.http.AuthChallengeResponse
import com.lnight.valence.server.ChallengeTestSupport.ISSUED_AT_MILLIS
import com.lnight.valence.server.ChallengeTestSupport.LIFETIME_MILLIS
import com.lnight.valence.server.domain.ChallengeCheck
import kotlin.test.Test
import kotlin.test.assertEquals

class ChallengeServiceTest {
    private val issued = ChallengeTestSupport.service().issue()

    @Test
    fun `issued challenge matches the protocol fixture`() {
        val fixture =
            ProtocolJson.json.decodeFromString(
                AuthChallengeResponse.serializer(),
                GoldenFixtures.read("auth-challenge-response"),
            )

        assertEquals(fixture.challenge, Base64Url.encode(issued.bytes))
        assertEquals(fixture.expiresAt, issued.expiresAtMillis)
    }

    @Test
    fun `a fresh challenge verifies on a separate instance`() {
        val verifier = ChallengeTestSupport.service()

        assertEquals(ChallengeCheck.Valid, verifier.verify(issued.bytes))
    }

    @Test
    fun `a challenge is valid until the last millisecond of its lifetime`() {
        val verifier = ChallengeTestSupport.service(atMillis = ISSUED_AT_MILLIS + LIFETIME_MILLIS - 1)

        assertEquals(ChallengeCheck.Valid, verifier.verify(issued.bytes))
    }

    @Test
    fun `a challenge expires when its lifetime ends`() {
        val verifier = ChallengeTestSupport.service(atMillis = ISSUED_AT_MILLIS + LIFETIME_MILLIS)

        assertEquals(ChallengeCheck.Expired, verifier.verify(issued.bytes))
    }

    @Test
    fun `a modified mac is forged`() {
        val tampered = issued.bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        assertEquals(ChallengeCheck.Forged, ChallengeTestSupport.service().verify(tampered))
    }

    @Test
    fun `a modified expiry is forged`() {
        val expiryIndex = 23
        val tampered = issued.bytes.copyOf().also { it[expiryIndex] = (it[expiryIndex].toInt() xor 1).toByte() }

        assertEquals(ChallengeCheck.Forged, ChallengeTestSupport.service().verify(tampered))
    }

    @Test
    fun `a challenge from another key is forged`() {
        val otherKey = ByteArray(32) { it.toByte() }

        assertEquals(ChallengeCheck.Forged, ChallengeTestSupport.service(key = otherKey).verify(issued.bytes))
    }

    @Test
    fun `a challenge of the wrong length is malformed`() {
        assertEquals(ChallengeCheck.Malformed, ChallengeTestSupport.service().verify(ByteArray(55)))
    }
}
