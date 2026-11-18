package com.lnight.valence.server.crypto

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.server.ServerConfig
import org.signal.libsignal.protocol.ecc.ECKeyPair

private const val SERVER_KEY_ID = 1

fun main() {
    val trustRoot = ECKeyPair.generate()
    val serverKey = ECKeyPair.generate()
    val serverCertificate =
        CertificateAuthority()
            .createServerCertificate(trustRoot.privateKey.serialize(), SERVER_KEY_ID, serverKey.publicKey.serialize())

    println("Pin this trust root public key in the app:")
    println("  ${Base64Url.encode(trustRoot.publicKey.serialize())}")
    println("Keep this trust root private key OFFLINE. It must never reach the server:")
    println("  ${Base64Url.encode(trustRoot.privateKey.serialize())}")
    println("Server configuration:")
    println("  ${ServerConfig.SERVER_CERTIFICATE_VARIABLE}=${Base64Url.encode(serverCertificate)}")
    println("  ${ServerConfig.CERTIFICATE_KEY_VARIABLE}=${Base64Url.encode(serverKey.privateKey.serialize())}")
}
