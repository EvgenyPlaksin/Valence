package com.lnight.valence.core.database

import com.lnight.valence.core.model.MessageId
import com.lnight.valence.core.model.Outbox
import com.lnight.valence.core.model.OutboxState
import javax.inject.Inject

internal class OutboxEntityMapper
    @Inject
    constructor() {
        fun toDomain(entity: OutboxEntity): Outbox =
            Outbox(
                messageId = MessageId(entity.messageId),
                state = OutboxState.entries[entity.state],
                attemptCount = entity.attemptCount,
                nextAttemptAtMillis = entity.nextAttemptAtMillis,
                expiresAtMillis = entity.expiresAtMillis,
            )

        fun toEntity(
            domain: Outbox,
            envelopeBlob: ByteArray? = null,
            lastErrorCode: Int? = null,
        ): OutboxEntity =
            OutboxEntity(
                messageId = domain.messageId.value,
                envelopeBlob = envelopeBlob,
                attemptCount = domain.attemptCount,
                nextAttemptAtMillis = domain.nextAttemptAtMillis,
                state = domain.state.ordinal,
                expiresAtMillis = domain.expiresAtMillis,
                lastErrorCode = lastErrorCode,
            )
    }
