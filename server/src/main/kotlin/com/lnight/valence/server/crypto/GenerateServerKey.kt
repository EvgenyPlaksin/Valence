package com.lnight.valence.server.crypto

import com.lnight.valence.server.ServerConfig

@JvmName("main")
fun generateServerKey(args: Array<String>) {
    val outputDirectory = keyOutputDirectory(args)
    val pair = KeyCeremony().generateKeyPair()
    val privateKeyFile = PrivateKeyFiles.write(outputDirectory, "server-signing-private.key", pair.privateKey)
    println("Server signing public key. Send this to whoever holds the trust root:")
    println("  ${pair.publicKey}")
    println("Server signing private key saved with owner-only permissions.")
    println("Set ${ServerConfig.CERTIFICATE_KEY_VARIABLE} from this file:")
    println("  $privateKeyFile")
}
