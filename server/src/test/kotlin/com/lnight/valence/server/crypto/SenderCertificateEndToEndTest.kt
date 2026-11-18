package com.lnight.valence.server.crypto

import com.lnight.valence.protocol.MailboxIds
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.protocol.ecc.ECKeyPair
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class SenderCertificateEndToEndTest {
    private val mailboxId = "mbx_g4r2k7x5qmwv6bnzdcs3n4pe5a"
    private val now = 1_760_000_000_000L
    private val expiresAt = now + 86_400_000L
    private val trustRoot = ECKeyPair.generate()
    private val serverKey = ECKeyPair.generate()
    private val identity = ECKeyPair.generate()
    private val serverCertificate =
        CertificateAuthority()
            .createServerCertificate(trustRoot.privateKey.serialize(), 1, serverKey.publicKey.serialize())
    private val issuer = LibsignalSenderCertificateIssuer(serverCertificate, serverKey.privateKey.serialize())

    private fun issued(): ByteArray = issuer.issue(mailboxId, identity.publicKey.serialize(), expiresAt)

    private fun validator(root: ECKeyPair = trustRoot) = CertificateValidator(root.publicKey)

    @Test
    fun `libsignal accepts the certificate chain`() {
        validator().validate(SenderCertificate(issued()), now)
    }

    @Test
    fun `the certificate carries the sender identity key device and expiry`() {
        val certificate = SenderCertificate(issued())

        assertContentEquals(identity.publicKey.serialize(), certificate.key.serialize())
        assertEquals(expiresAt, certificate.expiration)
        assertEquals(1, certificate.senderDeviceId)
    }

    @Test
    fun `the certificate names the sender by the uuid form of the mailbox id`() {
        assertEquals(MailboxIds.toUuid(mailboxId), SenderCertificate(issued()).senderUuid)
    }

    @Test
    fun `an expired certificate is rejected`() {
        assertFails { validator().validate(SenderCertificate(issued()), expiresAt + 1) }
    }

    @Test
    fun `a certificate from another trust root is rejected`() {
        assertFails { validator(ECKeyPair.generate()).validate(SenderCertificate(issued()), now) }
    }

    @Test
    fun `a tampered certificate is rejected`() {
        val tampered = issued().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }

        assertFails { validator().validate(SenderCertificate(tampered), now) }
    }

    @Test
    fun `a signing key that does not match the server certificate is refused at startup`() {
        assertFailsWith<IllegalArgumentException> {
            LibsignalSenderCertificateIssuer(serverCertificate, ECKeyPair.generate().privateKey.serialize())
        }
    }
}
