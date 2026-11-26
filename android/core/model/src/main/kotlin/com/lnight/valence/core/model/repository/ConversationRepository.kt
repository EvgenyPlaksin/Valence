package com.lnight.valence.core.model.repository

import com.lnight.valence.core.model.Conversation
import com.lnight.valence.core.model.ConversationId
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>

    fun observeConversation(conversationId: ConversationId): Flow<Conversation?>
}
