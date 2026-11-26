package com.lnight.valence.server.crypto

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.SignedMessages
import com.lnight.valence.protocol.http.KyberPreKeyDto
import com.lnight.valence.protocol.http.OneTimePreKeyDto
import com.lnight.valence.protocol.http.RegisterRequest
import com.lnight.valence.protocol.http.SignedPreKeyDto
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

private const val EXPIRED_TOKEN_AGE_SECONDS = 7_200L

internal class FullStackClientKeys(
    val mailboxId: String,
) {
    val identity = ECKeyPair.generate()
    val deliveryKey = secureBytes(16)
    val deliveryKeyHash = MessageDigest.getInstance("SHA-256").digest(deliveryKey)
    private val signedEc = signedEc(1)
    private val signedKyber = signedKyber(1)
    private val ecOneTime = listOf(oneTimeEc(10), oneTimeEc(11))
    private val kyberOneTime = listOf(signedKyber(20), signedKyber(21))

    fun registration(
        challenge: String,
        version: Int = 2,
    ): RegisterRequest {
        val challengeBytes = Base64Url.decode(challenge)
        val identityPublicKey = identity.publicKey.serialize()
        val proof =
            identity.privateKey.calculateSignature(
                SignedMessages.register(challengeBytes, mailboxId, identityPublicKey, deliveryKeyHash),
            )
        return RegisterRequest(
            v = version,
            challenge = challenge,
            mailboxId = mailboxId,
            identityKey = Base64Url.encode(identityPublicKey),
            signedPreKey = signedEc,
            kyberPreKey = signedKyber,
            oneTimePreKeys = ecOneTime,
            oneTimeKyberPreKeys = kyberOneTime,
            deliveryKeyHash = Base64Url.encode(deliveryKeyHash),
            proof = Base64Url.encode(proof),
        )
    }

    fun loginSignature(challenge: String): String =
        Base64Url.encode(
            identity.privateKey.calculateSignature(
                SignedMessages.login(Base64Url.decode(challenge), mailboxId),
            ),
        )

    fun newSignedEc(id: Int): SignedPreKeyDto = signedEc(id)

    fun newSignedKyber(id: Int): KyberPreKeyDto = signedKyber(id)

    fun newOneTimeEc(id: Int): OneTimePreKeyDto = oneTimeEc(id)

    fun newOneTimeKyber(id: Int): KyberPreKeyDto = signedKyber(id)

    private fun signedEc(id: Int): SignedPreKeyDto {
        val keyPair = ECKeyPair.generate()
        val publicKey = keyPair.publicKey.serialize()
        return SignedPreKeyDto(
            id = id,
            pub = Base64Url.encode(publicKey),
            sig = Base64Url.encode(identity.privateKey.calculateSignature(publicKey)),
        )
    }

    private fun signedKyber(id: Int): KyberPreKeyDto {
        val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val publicKey = keyPair.publicKey.serialize()
        return KyberPreKeyDto(
            id = id,
            pub = Base64Url.encode(publicKey),
            sig = Base64Url.encode(identity.privateKey.calculateSignature(publicKey)),
        )
    }

    private fun oneTimeEc(id: Int): OneTimePreKeyDto {
        val keyPair = ECKeyPair.generate()
        return OneTimePreKeyDto(id = id, pub = Base64Url.encode(keyPair.publicKey.serialize()))
    }
}

internal class FullStackCertificateFixtures {
    private val trustRoot = ECKeyPair.generate()
    private val serverSigningKey = ECKeyPair.generate()
    val trustRootPublicKey = trustRoot.publicKey.serialize()
    private val serverCertificate =
        CertificateAuthority().createServerCertificate(
            trustRoot.privateKey.serialize(),
            1,
            serverSigningKey.publicKey.serialize(),
        )
    val senderCertificateIssuer =
        LibsignalSenderCertificateIssuer(
            serverCertificate,
            serverSigningKey.privateKey.serialize(),
        )

    fun verifySenderCertificate(
        bytes: ByteArray,
        at: Instant,
    ) {
        CertificateValidator(ECPublicKey(trustRootPublicKey)).validate(SenderCertificate(bytes), at.toEpochMilli())
    }
}

internal fun verifyPreKeySignature(
    identityKey: ByteArray,
    publicKey: ByteArray,
    signature: ByteArray,
) {
    check(LibsignalPublicKeyVerifier().verifySignature(identityKey, publicKey, signature))
}

internal fun expiredToken(
    mailboxId: String,
    signingKey: ByteArray,
): String =
    JwtAccessTokenIssuer(
        Clock.fixed(Instant.now().minusSeconds(EXPIRED_TOKEN_AGE_SECONDS), ZoneOffset.UTC),
        signingKey,
    ).issue(mailboxId).value

internal fun unsignedToken(mailboxId: String): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray(Charsets.UTF_8))
    val claims = encoder.encodeToString("""{"sub":"$mailboxId","iat":1,"exp":4102444800}""".toByteArray(Charsets.UTF_8))
    return "$header.$claims."
}

private fun secureBytes(size: Int) = ByteArray(size).also(SecureRandom()::nextBytes)
