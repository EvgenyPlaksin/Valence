package com.lnight.valence.server.crypto

import com.lnight.valence.protocol.MailboxIds
import com.lnight.valence.server.domain.SenderCertificateIssuer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.signal.libsignal.protocol.ecc.ECPrivateKey

@OptIn(ExperimentalSerializationApi::class)
class LibsignalSenderCertificateIssuer(
    serverCertificate: ByteArray,
    signingKey: ByteArray,
) : SenderCertificateIssuer {
    private val signer = ProtoBuf.decodeFromByteArray(ServerCertificateEnvelope.serializer(), serverCertificate)
    private val privateKey = ECPrivateKey(signingKey)

    init {
        val body =
            ProtoBuf.decodeFromByteArray(
                ServerCertificateBody.serializer(),
                requireNotNull(signer.certificate) { "The server certificate has no body" },
            )
        require(privateKey.getPublicKey().serialize().contentEquals(body.key)) {
            "The certificate signing key does not match the server certificate"
        }
    }

    override fun issue(
        mailboxId: String,
        identityKey: ByteArray,
        expiresAtMillis: Long,
    ): ByteArray {
        val senderUuid = requireNotNull(MailboxIds.toUuid(mailboxId)) { "Not a mailbox id" }
        val body =
            ProtoBuf.encodeToByteArray(
                SenderCertificateBody.serializer(),
                SenderCertificateBody(
                    senderDevice = DEVICE_ID,
                    expires = expiresAtMillis,
                    identityKey = identityKey,
                    signer = signer,
                    senderUuid = senderUuid,
                ),
            )
        return ProtoBuf.encodeToByteArray(
            SenderCertificateEnvelope.serializer(),
            SenderCertificateEnvelope(body, privateKey.calculateSignature(body)),
        )
    }

    private companion object {
        const val DEVICE_ID = 1
    }
}
