package com.lnight.valence.server.crypto

private const val SERVER_KEY_ID = 1

@JvmName("main")
fun generateCertificateKeys(args: Array<String>) {
    val outputDirectory = keyOutputDirectory(args)
    val ceremony = KeyCeremony()
    val trustRoot = ceremony.generateKeyPair()
    val serverKey = ceremony.generateKeyPair()
    val serverCertificate = ceremony.signServerCertificate(trustRoot.privateKey, serverKey.publicKey, SERVER_KEY_ID)

    val trustRootFile = PrivateKeyFiles.write(outputDirectory, "trust-root-private.key", trustRoot.privateKey)
    val serverKeyFile = PrivateKeyFiles.write(outputDirectory, "server-signing-private.key", serverKey.privateKey)
    val certificateFile = outputDirectory.resolve("server-certificate.txt")
    check(
        !java.nio.file.Files
            .exists(certificateFile),
    ) { "The server certificate file already exists" }
    java.nio.file.Files
        .writeString(certificateFile, serverCertificate, Charsets.UTF_8)

    println("DEVELOPMENT ONLY. Do not use these keys in production.")
    println("Trust root public key:")
    println("  ${trustRoot.publicKey}")
    println("Server certificate saved to:")
    println("  $certificateFile")
    println("Trust root private key saved with owner-only permissions:")
    println("  $trustRootFile")
    println("Server signing private key saved with owner-only permissions:")
    println("  $serverKeyFile")
    println("Server configuration values are stored in the files above.")
}
