package com.lnight.valence.server

import com.lnight.valence.server.crypto.HmacChallengeAuthenticator
import com.lnight.valence.server.domain.ChallengeService
import com.lnight.valence.server.domain.RandomBytes
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal object ChallengeTestSupport {
    const val ISSUED_AT_MILLIS = 1_760_000_000_000L
    const val LIFETIME_MILLIS = 60_000L

    private val demoKey = "demo-server-challenge-key-not-real".toByteArray(Charsets.UTF_8)
    private val demoNonce = "a3f19c0d52e7b84164d0f7aa3c19e285".hexToByteArray()

    fun service(
        atMillis: Long = ISSUED_AT_MILLIS,
        key: ByteArray = demoKey,
    ): ChallengeService =
        ChallengeService(
            clock = Clock.fixed(Instant.ofEpochMilli(atMillis), ZoneOffset.UTC),
            random = RandomBytes { size -> demoNonce.copyOf(size) },
            authenticator = HmacChallengeAuthenticator(key),
        )
}
