package com.lnight.valence.server.crypto

import com.lnight.valence.protocol.Base64Url
import org.signal.libsignal.protocol.ecc.ECKeyPair

class EncodedKeyPair(
    val privateKey: String,
    val publicKey: String,
)

class KeyCeremony {
    fun generateKeyPair(): EncodedKeyPair {
        val pair = ECKeyPair.generate()
        return EncodedKeyPair(
            privateKey = Base64Url.encode(pair.privateKey.serialize()),
            publicKey = Base64Url.encode(pair.publicKey.serialize()),
        )
    }

    fun signServerCertificate(
        trustRootPrivateKey: String,
        serverPublicKey: String,
        keyId: Int,
    ): String =
        Base64Url.encode(
            CertificateAuthority()
                .createServerCertificate(
                    Base64Url.decode(trustRootPrivateKey),
                    keyId,
                    Base64Url.decode(serverPublicKey),
                ),
        )
}
