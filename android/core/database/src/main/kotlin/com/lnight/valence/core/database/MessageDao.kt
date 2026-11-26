package com.lnight.valence.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface MessageDao {
    @Query("SELECT * FROM message WHERE conversation_id = :conversationId ORDER BY sent_at DESC, message_id DESC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM message WHERE message_id = :messageId")
    suspend fun getMessage(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(entity: MessageEntity): Long

    @Query("UPDATE message SET status = :status, server_ts = :serverTsMillis WHERE message_id = :messageId")
    suspend fun updateSentStatus(
        messageId: String,
        status: Int,
        serverTsMillis: Long,
    )

    @Query("UPDATE message SET status = :status WHERE message_id = :messageId")
    suspend fun updateStatus(
        messageId: String,
        status: Int,
    )

    @Query("DELETE FROM message WHERE message_id = :messageId")
    suspend fun deleteMessage(messageId: String): Int
}
