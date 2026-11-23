package com.lnight.valence.server.crypto

import com.lnight.valence.server.ServerConfig
import kotlin.system.exitProcess

private const val TRUST_ROOT_VARIABLE = "VALENCE_TRUST_ROOT_PRIVATE_KEY"
private const val ARGUMENT_COUNT = 2

private class Arguments(
    val serverPublicKey: String,
    val keyId: Int,
)

private fun parse(args: Array<String>): Arguments? {
    val keyId = args.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 1 }
    return if (args.size == ARGUMENT_COUNT && keyId != null) Arguments(args[0], keyId) else null
}

@JvmName("main")
fun signServerCertificate(args: Array<String>) {
    val trustRoot = System.getenv(TRUST_ROOT_VARIABLE)
    val arguments = parse(args)
    if (trustRoot == null || arguments == null) {
        System.err.println("Set $TRUST_ROOT_VARIABLE, then pass the server public key and a key id of at least 1.")
        exitProcess(1)
    }
    val certificate = KeyCeremony().signServerCertificate(trustRoot, arguments.serverPublicKey, arguments.keyId)
    println("Server certificate. Set it as ${ServerConfig.SERVER_CERTIFICATE_VARIABLE}:")
    println("  $certificate")
}
