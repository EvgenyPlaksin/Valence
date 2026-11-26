package com.lnight.valence.core.database

import com.lnight.valence.core.model.MessageId
import com.lnight.valence.core.model.Outbox
import com.lnight.valence.core.model.OutboxState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutboxEntityMapperTest {
    private val mapper = OutboxEntityMapper()

    @Test
    fun mapsToDomainAndEntityCorrectly() {
        val domain =
            Outbox(
                messageId = MessageId("msg-1"),
                state = OutboxState.Ready,
                attemptCount = 2,
                nextAttemptAtMillis = 3000L,
                expiresAtMillis = 50000L,
            )
        val envelope = byteArrayOf(9, 8, 7)

        val entity = mapper.toEntity(domain, envelopeBlob = envelope, lastErrorCode = 401)
        assertEquals("msg-1", entity.messageId)
        assertArrayEquals(envelope, entity.envelopeBlob)
        assertEquals(2, entity.attemptCount)
        assertEquals(3000L, entity.nextAttemptAtMillis)
        assertEquals(OutboxState.Ready.ordinal, entity.state)
        assertEquals(50000L, entity.expiresAtMillis)
        assertEquals(401, entity.lastErrorCode)

        val mapped = mapper.toDomain(entity)
        assertEquals(domain, mapped)
    }

    @Test
    fun mapsWithNullEnvelopeAndLastError() {
        val domain =
            Outbox(
                messageId = MessageId("msg-2"),
                state = OutboxState.PausedDeliveryKey,
                attemptCount = 0,
                nextAttemptAtMillis = 1000L,
                expiresAtMillis = 20000L,
            )

        val entity = mapper.toEntity(domain)
        assertNull(entity.envelopeBlob)
        assertNull(entity.lastErrorCode)
        assertEquals(OutboxState.PausedDeliveryKey.ordinal, entity.state)

        val mapped = mapper.toDomain(entity)
        assertEquals(domain, mapped)
    }
}
