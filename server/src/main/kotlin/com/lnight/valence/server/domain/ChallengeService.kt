package com.lnight.valence.server.domain

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration

class ChallengeService(
    private val clock: Clock,
    private val random: RandomBytes,
    private val authenticator: ChallengeAuthenticator,
    private val lifetime: Duration = DEFAULT_LIFETIME,
) {
    fun issue(): Challenge {
        val nonce = random.next(NONCE_SIZE)
        val expiresAt = clock.millis() + lifetime.toMillis()
        val payload = nonce + ByteBuffer.allocate(EXPIRY_SIZE).putLong(expiresAt).array()
        return Challenge(
            bytes = payload + authenticator.sign(macInput(payload)),
            expiresAtMillis = expiresAt,
        )
    }

    fun verify(challenge: ByteArray): ChallengeCheck {
        if (challenge.size != CHALLENGE_SIZE) return ChallengeCheck.Malformed
        val payload = challenge.copyOfRange(0, PAYLOAD_SIZE)
        val mac = challenge.copyOfRange(PAYLOAD_SIZE, CHALLENGE_SIZE)
        val genuine = MessageDigest.isEqual(authenticator.sign(macInput(payload)), mac)
        return when {
            !genuine -> ChallengeCheck.Forged
            expiresAt(payload) <= clock.millis() -> ChallengeCheck.Expired
            else -> ChallengeCheck.Valid
        }
    }

    private fun macInput(payload: ByteArray): ByteArray = TAG + payload

    private fun expiresAt(payload: ByteArray): Long = ByteBuffer.wrap(payload, NONCE_SIZE, EXPIRY_SIZE).long

    private companion object {
        const val NONCE_SIZE = 16
        const val EXPIRY_SIZE = Long.SIZE_BYTES
        const val MAC_SIZE = 32
        const val PAYLOAD_SIZE = NONCE_SIZE + EXPIRY_SIZE
        const val CHALLENGE_SIZE = PAYLOAD_SIZE + MAC_SIZE
        const val LIFETIME_SECONDS = 60L

        val TAG = "valence/challenge/v1".toByteArray(Charsets.UTF_8)
        val DEFAULT_LIFETIME: Duration = Duration.ofSeconds(LIFETIME_SECONDS)
    }
}
