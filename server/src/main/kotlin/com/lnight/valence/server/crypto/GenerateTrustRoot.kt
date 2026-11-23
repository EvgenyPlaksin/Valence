package com.lnight.valence.server.crypto

@JvmName("main")
fun generateTrustRoot(args: Array<String>) {
    val outputDirectory = keyOutputDirectory(args)
    val pair = KeyCeremony().generateKeyPair()
    val privateKeyFile = PrivateKeyFiles.write(outputDirectory, "trust-root-private.key", pair.privateKey)
    println("Trust root public key. Pin this in the app:")
    println("  ${pair.publicKey}")
    println("Trust root private key saved with owner-only permissions:")
    println("  $privateKeyFile")
}
