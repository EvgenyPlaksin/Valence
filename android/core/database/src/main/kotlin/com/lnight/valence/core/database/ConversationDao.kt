package com.lnight.valence.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ConversationDao {
    @Query("SELECT * FROM conversation ORDER BY pinned DESC, last_message_at DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE conversation_id = :conversationId")
    fun observeConversation(conversationId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversation WHERE conversation_id = :conversationId")
    suspend fun getConversation(conversationId: String): ConversationEntity?

    @Query("SELECT * FROM conversation WHERE contact_id = :contactId")
    suspend fun getConversationByContactId(contactId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(entity: ConversationEntity)

    @Update
    suspend fun updateConversation(entity: ConversationEntity)

    @Query(
        """
        UPDATE conversation
        SET last_message_id = :lastMessageId,
            last_message_at = :lastMessageAtMillis,
            unread_count = unread_count + :unreadDelta
        WHERE conversation_id = :conversationId
        """,
    )
    suspend fun updateSummary(
        conversationId: String,
        lastMessageId: String,
        lastMessageAtMillis: Long,
        unreadDelta: Int,
    )

    @Query("DELETE FROM conversation WHERE conversation_id = :conversationId")
    suspend fun deleteConversation(conversationId: String): Int
}
