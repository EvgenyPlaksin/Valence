package com.lnight.valence.server.domain

import java.time.Clock
import java.time.Duration

class SendMessageService(
    private val authenticator: DeliveryKeyAuthenticator,
    private val inbox: InboxStore,
    private val clock: Clock,
) {
    fun send(
        mailboxId: String,
        deliveryKey: ByteArray,
        envelope: ByteArray,
        ttlSeconds: Long?,
    ): SendResult {
        val mailbox =
            authenticator.authenticate(mailboxId, deliveryKey)
                ?: return SendResult.Rejected(SendRejection.UNAUTHORIZED)
        val rejection = requestRejection(envelope, ttlSeconds)
        return if (rejection != null) SendResult.Rejected(rejection) else append(mailbox.id, envelope, ttlSeconds)
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

    private fun append(
        mailboxId: String,
        envelope: ByteArray,
        ttlSeconds: Long?,
    ): SendResult {
        val now = clock.millis()
        val ttl = minOf(ttlSeconds ?: MessageLimits.MAX_TTL_SECONDS, MessageLimits.MAX_TTL_SECONDS)
        val message = NewMessage(envelope, now, now + Duration.ofSeconds(ttl).toMillis())
        return when (inbox.append(mailboxId, message, MessageLimits.MAX_QUEUED_PER_MAILBOX)) {
            is AppendResult.Appended -> SendResult.Accepted(now)
            AppendResult.QuotaExceeded -> SendResult.Rejected(SendRejection.QUOTA_EXCEEDED)
        }
    }
}
