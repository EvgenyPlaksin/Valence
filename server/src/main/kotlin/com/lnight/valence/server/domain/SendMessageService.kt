package com.lnight.valence.server.domain

import java.time.Clock
import java.time.Duration

class SendMessageService(
    private val authenticator: DeliveryKeyAuthenticator,
    private val inbox: InboxStore,
    private val notifier: MessageNotifier,
    private val limiter: RateLimiter,
    private val mailboxPolicy: RateLimitPolicy,
    private val clock: Clock,
) {
    suspend fun send(
        mailboxId: String,
        deliveryKey: ByteArray,
        envelope: ByteArray,
        ttlSeconds: Long?,
    ): SendResult {
        val mailbox =
            authenticator.authenticate(mailboxId, deliveryKey)
                ?: return SendResult.Rejected(SendRejection.UNAUTHORIZED)
        val rejection = requestRejection(envelope, ttlSeconds)
        return if (rejection == null) {
            append(mailbox, envelope, ttlSeconds)
        } else {
            when (
                val authorization =
                    inbox.authorize(mailbox.id, mailbox.deliveryKeyHash) {
                        limiter.tryAcquire("send:mailbox:${mailbox.id}", mailboxPolicy)
                    }
            ) {
                DeliveryAuthorizationResult.Authorized -> SendResult.Rejected(rejection)
                DeliveryAuthorizationResult.Unauthorized -> SendResult.Rejected(SendRejection.UNAUTHORIZED)
                is DeliveryAuthorizationResult.RateLimited -> SendResult.RateLimited(authorization.retryAfterMillis)
            }
        }
    }

    private fun requestRejection(
        envelope: ByteArray,
        ttlSeconds: Long?,
    ): SendRejection? =
        when {
            envelope.isEmpty() || (ttlSeconds != null && ttlSeconds < MessageLimits.MIN_TTL_SECONDS) -> {
                SendRejection.INVALID_REQUEST
            }

            envelope.size > MessageLimits.ENVELOPE_BYTES -> {
                SendRejection.TOO_LARGE
            }

            else -> {
                null
            }
        }

    private suspend fun append(
        mailbox: Mailbox,
        envelope: ByteArray,
        ttlSeconds: Long?,
    ): SendResult {
        val now = clock.millis()
        val ttl = minOf(ttlSeconds ?: MessageLimits.MAX_TTL_SECONDS, MessageLimits.MAX_TTL_SECONDS)
        val message = NewMessage(envelope, now, now + Duration.ofSeconds(ttl).toMillis())
        return when (
            val result =
                inbox.append(
                    mailbox.id,
                    mailbox.deliveryKeyHash,
                    message,
                    MessageLimits.MAX_QUEUED_PER_MAILBOX,
                ) { limiter.tryAcquire("send:mailbox:${mailbox.id}", mailboxPolicy) }
        ) {
            is AppendResult.Appended -> {
                notifier.messageStored(mailbox.id)
                SendResult.Accepted(now)
            }

            AppendResult.QuotaExceeded -> {
                SendResult.Rejected(SendRejection.QUOTA_EXCEEDED)
            }

            AppendResult.Unauthorized -> {
                SendResult.Rejected(SendRejection.UNAUTHORIZED)
            }

            is AppendResult.RateLimited -> {
                SendResult.RateLimited(result.retryAfterMillis)
            }
        }
    }
}
