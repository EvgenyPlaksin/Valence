package com.lnight.valence.server.domain

class PreKeyBundleService(
    private val authenticator: DeliveryKeyAuthenticator,
    private val store: MailboxStore,
    private val limiter: RateLimiter,
    private val mailboxPolicy: RateLimitPolicy,
) {
    fun fetch(
        mailboxId: String,
        deliveryKey: ByteArray,
    ): BundleResult {
        val mailbox = authenticator.authenticate(mailboxId, deliveryKey) ?: return BundleResult.Unauthorized
        return when (val decision = limiter.tryAcquire("bundle:mailbox:${mailbox.id}", mailboxPolicy)) {
            is RateLimitDecision.Limited -> {
                BundleResult.RateLimited(decision.retryAfterMillis)
            }

            RateLimitDecision.Allowed -> {
                store.takeBundle(mailbox.id)?.let { BundleResult.Found(it) }
                    ?: BundleResult.Unauthorized
            }
        }
    }
}
