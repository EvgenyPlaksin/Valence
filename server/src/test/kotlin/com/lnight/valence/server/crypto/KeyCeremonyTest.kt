package com.lnight.valence.server.crypto

import com.lnight.valence.protocol.Base64Url
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class KeyCeremonyTest {
    private val ceremony = KeyCeremony()
    private val now = 1_760_000_000_000L
    private val mailboxId = "mbx_g4r2k7x5qmwv6bnzdcs3n4pe5a"

    private fun issue(
        trustRoot: EncodedKeyPair,
        serverKey: EncodedKeyPair,
    ): ByteArray {
        val certificate = ceremony.signServerCertificate(trustRoot.privateKey, serverKey.publicKey, 7)
        val issuer =
            LibsignalSenderCertificateIssuer(Base64Url.decode(certificate), Base64Url.decode(serverKey.privateKey))
        return issuer.issue(mailboxId, ECKeyPair.generate().publicKey.serialize(), now + 86_400_000L)
    }

    private fun validator(trustRoot: EncodedKeyPair) =
        CertificateValidator(ECPublicKey(Base64Url.decode(trustRoot.publicKey)))

    @Test
    fun `a generated key pair is a consistent libsignal key pair`() {
        val pair = ceremony.generateKeyPair()

        val privateKey = ECPrivateKey(Base64Url.decode(pair.privateKey))

        assertContentEquals(Base64Url.decode(pair.publicKey), privateKey.getPublicKey().serialize())
    }

    @Test
    fun `two generated pairs differ`() {
        assertNotEquals(ceremony.generateKeyPair().privateKey, ceremony.generateKeyPair().privateKey)
    }

    @Test
    fun `the ceremony produces a chain that libsignal validates`() {
        val trustRoot = ceremony.generateKeyPair()

        val bytes = issue(trustRoot, ceremony.generateKeyPair())

        validator(trustRoot).validate(SenderCertificate(bytes), now)
    }

    @Test
    fun `a chain from another trust root is not accepted`() {
        val bytes = issue(ceremony.generateKeyPair(), ceremony.generateKeyPair())

        assertFails { validator(ceremony.generateKeyPair()).validate(SenderCertificate(bytes), now) }
    }

    @Test
    fun `malformed inputs are rejected`() {
        assertFails { ceremony.signServerCertificate("!!!", "!!!", 1) }
    }
}
