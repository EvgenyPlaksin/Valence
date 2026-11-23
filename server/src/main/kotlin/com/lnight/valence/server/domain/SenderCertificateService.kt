package com.lnight.valence.server.domain

import java.time.Clock
import java.time.Duration

class SenderCertificateService(
    private val store: MailboxStore,
    private val issuer: SenderCertificateIssuer,
    private val clock: Clock,
) {
    suspend fun issue(mailboxId: String): IssuedCertificate? {
        val mailbox = store.find(mailboxId) ?: return null
        val expiresAt = clock.millis() + LIFETIME.toMillis()
        return IssuedCertificate(issuer.issue(mailbox.id, mailbox.identityKey, expiresAt), expiresAt)
    }

    private companion object {
        val LIFETIME: Duration = Duration.ofHours(24)
    }
}
