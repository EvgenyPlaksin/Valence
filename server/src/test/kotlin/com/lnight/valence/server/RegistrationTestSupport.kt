package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.http.AuthTokenRequest
import com.lnight.valence.protocol.http.KyberPreKeyDto
import com.lnight.valence.protocol.http.OneTimePreKeyDto
import com.lnight.valence.protocol.http.RegisterRequest
import com.lnight.valence.protocol.http.SignedPreKeyDto
import com.lnight.valence.server.domain.AccessToken
import com.lnight.valence.server.domain.AccessTokenIssuer
import com.lnight.valence.server.domain.Mailbox
import com.lnight.valence.server.domain.MailboxStore
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.PublicKeyVerifier
import com.lnight.valence.server.domain.Registration
import com.lnight.valence.server.domain.RegistrationService
import com.lnight.valence.server.domain.SignedPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal object RegistrationTestSupport {
    const val MAILBOX_ID = "mbx_g4r2k7x5qmwv6bnzdcs3n4pe5a"
    const val TOKEN_EXPIRY_MILLIS = 1_760_003_601_000L

    val validProof = ByteArray(64) { 1 }
    val signedPreKeySignature = ByteArray(64) { 2 }
    val kyberPreKeySignature = ByteArray(64) { 3 }

    fun registration(
        challenge: ByteArray = ChallengeTestSupport.service().issue().bytes,
        mailboxId: String = MAILBOX_ID,
        signedPreKey: SignedPublicKey = SignedPublicKey(1, ByteArray(33) { 5 }, signedPreKeySignature),
        kyberPreKey: SignedPublicKey = SignedPublicKey(1, ByteArray(1569) { 6 }, kyberPreKeySignature),
        oneTimePreKeys: List<OneTimePreKey> = defaultOneTimePreKeys(),
        deliveryKeyHash: ByteArray = ByteArray(32) { 9 },
        proof: ByteArray = validProof,
    ): Registration =
        Registration(
            challenge = challenge,
            mailboxId = mailboxId,
            identityKey = ByteArray(33) { 4 },
            signedPreKey = signedPreKey,
            kyberPreKey = kyberPreKey,
            oneTimePreKeys = oneTimePreKeys,
            deliveryKeyHash = deliveryKeyHash,
            proof = proof,
        )

    fun defaultOneTimePreKeys(): List<OneTimePreKey> =
        listOf(OneTimePreKey(1, ByteArray(33) { 7 }), OneTimePreKey(2, ByteArray(33) { 8 }))

    fun registerRequest(): RegisterRequest =
        RegisterRequest(
            challenge = Base64Url.encode(ChallengeTestSupport.service().issue().bytes),
            mailboxId = MAILBOX_ID,
            identityKey = Base64Url.encode(ByteArray(33) { 4 }),
            signedPreKey =
                SignedPreKeyDto(1, Base64Url.encode(ByteArray(33) { 5 }), Base64Url.encode(signedPreKeySignature)),
            kyberPreKey =
                KyberPreKeyDto(1, Base64Url.encode(ByteArray(1569) { 6 }), Base64Url.encode(kyberPreKeySignature)),
            oneTimePreKeys =
                listOf(
                    OneTimePreKeyDto(1, Base64Url.encode(ByteArray(33) { 7 })),
                    OneTimePreKeyDto(2, Base64Url.encode(ByteArray(33) { 8 })),
                ),
            deliveryKeyHash = Base64Url.encode(ByteArray(32) { 9 }),
            proof = Base64Url.encode(validProof),
        )

    fun mailbox(identityKey: ByteArray = ByteArray(33) { 4 }): Mailbox =
        Mailbox(
            id = MAILBOX_ID,
            identityKey = identityKey,
            signedPreKey = SignedPublicKey(1, ByteArray(33) { 5 }, signedPreKeySignature),
            kyberPreKey = SignedPublicKey(1, ByteArray(1569) { 6 }, kyberPreKeySignature),
            deliveryKeyHash = ByteArray(32) { 9 },
            createdAtMillis = 0L,
        )

    fun authTokenRequest(): AuthTokenRequest =
        AuthTokenRequest(
            challenge = Base64Url.encode(ChallengeTestSupport.service().issue().bytes),
            mailboxId = MAILBOX_ID,
            signature = Base64Url.encode(ByteArray(64) { 1 }),
        )

    fun service(
        verifier: PublicKeyVerifier = FakePublicKeyVerifier(),
        store: MailboxStore = RecordingMailboxStore(),
        atMillis: Long = ChallengeTestSupport.ISSUED_AT_MILLIS,
    ): RegistrationService =
        RegistrationService(
            challenges = ChallengeTestSupport.service(atMillis),
            keyVerifier = verifier,
            store = store,
            tokens = AccessTokenIssuer { mailboxId -> AccessToken("token-for-$mailboxId", TOKEN_EXPIRY_MILLIS) },
            clock = Clock.fixed(Instant.ofEpochMilli(atMillis), ZoneOffset.UTC),
        )
}
