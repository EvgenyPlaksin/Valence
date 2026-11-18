package com.lnight.valence.server.domain

fun interface SenderCertificateIssuer {
    fun issue(
        mailboxId: String,
        identityKey: ByteArray,
        expiresAtMillis: Long,
    ): ByteArray
}
