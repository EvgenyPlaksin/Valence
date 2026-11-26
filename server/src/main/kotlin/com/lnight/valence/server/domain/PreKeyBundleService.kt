package com.lnight.valence.server.domain

class PreKeyBundleService(
    private val authenticator: DeliveryKeyAuthenticator,
    private val store: MailboxStore,
    private val limiter: RateLimiter,
    private val mailboxPolicy: RateLimitPolicy,
) {
    suspend fun fetch(
        mailboxId: String,
        deliveryKey: ByteArray,
    ): BundleResult {
        val mailbox = authenticator.authenticate(mailboxId, deliveryKey) ?: return BundleResult.Unauthorized
        return store.takeBundle(mailbox.id, mailbox.deliveryKeyHash) {
            limiter.tryAcquire("bundle:mailbox:${mailbox.id}", mailboxPolicy)
        }
    }
}
