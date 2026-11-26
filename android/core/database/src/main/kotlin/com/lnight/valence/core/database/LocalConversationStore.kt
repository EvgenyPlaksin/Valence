package com.lnight.valence.core.database

import com.lnight.valence.core.model.Conversation
import com.lnight.valence.core.model.ConversationId
import kotlinx.coroutines.flow.Flow

interface LocalConversationStore {
    fun observeConversations(): Flow<List<Conversation>>

    fun observeConversation(conversationId: ConversationId): Flow<Conversation?>

    suspend fun putConversation(conversation: Conversation)
}
