package com.lnight.valence.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutbox(entity: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE message_id = :messageId")
    suspend fun getOutbox(messageId: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE state = 0 AND next_attempt_at <= :nowMillis ORDER BY next_attempt_at ASC")
    suspend fun getDueOutboxItems(nowMillis: Long): List<OutboxEntity>

    @Query("UPDATE outbox SET envelope_blob = :envelopeBlob WHERE message_id = :messageId")
    suspend fun updateEnvelope(
        messageId: String,
        envelopeBlob: ByteArray,
    )

    @Query("DELETE FROM outbox WHERE message_id = :messageId")
    suspend fun deleteOutbox(messageId: String): Int

    @Query(
        """
        UPDATE outbox
        SET state = 1
        WHERE message_id IN (
            SELECT m.message_id
            FROM message m
            JOIN conversation c ON m.conversation_id = c.conversation_id
            WHERE c.contact_id = :contactId
        ) AND state = 0
        """,
    )
    suspend fun pauseForContact(contactId: String): Int

    @Query(
        """
        UPDATE outbox
        SET state = 0, next_attempt_at = :nowMillis
        WHERE message_id IN (
            SELECT m.message_id
            FROM message m
            JOIN conversation c ON m.conversation_id = c.conversation_id
            WHERE c.contact_id = :contactId
        ) AND state = 1
        """,
    )
    suspend fun resumeForContact(
        contactId: String,
        nowMillis: Long,
    ): Int

    @Transaction
    suspend fun failExpiredItems(
        nowMillis: Long,
        failedStatus: Int = 4,
    ): Int {
        val expiredIds = getExpiredOutboxMessageIds(nowMillis)
        if (expiredIds.isEmpty()) return 0
        markMessagesFailed(expiredIds, failedStatus)
        return deleteOutboxMessages(expiredIds)
    }

    @Query("SELECT message_id FROM outbox WHERE expires_at <= :nowMillis")
    suspend fun getExpiredOutboxMessageIds(nowMillis: Long): List<String>

    @Query("UPDATE message SET status = :failedStatus WHERE message_id IN (:messageIds)")
    suspend fun markMessagesFailed(
        messageIds: List<String>,
        failedStatus: Int,
    )

    @Query("DELETE FROM outbox WHERE message_id IN (:messageIds)")
    suspend fun deleteOutboxMessages(messageIds: List<String>): Int
}
