package com.lnight.valence.server.crypto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.signal.libsignal.protocol.ecc.ECPrivateKey

@OptIn(ExperimentalSerializationApi::class)
class CertificateAuthority {
    fun createServerCertificate(
        trustRootPrivateKey: ByteArray,
        keyId: Int,
        serverPublicKey: ByteArray,
    ): ByteArray {
        val body =
            ProtoBuf.encodeToByteArray(
                ServerCertificateBody.serializer(),
                ServerCertificateBody(keyId, serverPublicKey),
            )
        val signature = ECPrivateKey(trustRootPrivateKey).calculateSignature(body)
        return ProtoBuf.encodeToByteArray(
            ServerCertificateEnvelope.serializer(),
            ServerCertificateEnvelope(body, signature),
        )
    }
}
