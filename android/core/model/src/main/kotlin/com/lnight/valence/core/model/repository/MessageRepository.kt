package com.lnight.valence.core.model.repository

import com.lnight.valence.core.model.Message

interface MessageRepository {
    suspend fun enqueue(message: Message): MessageEnqueueResult
}

sealed interface MessageEnqueueResult {
    data object Enqueued : MessageEnqueueResult

    data class Failed(
        val reason: MessageEnqueueFailure,
    ) : MessageEnqueueResult
}

enum class MessageEnqueueFailure {
    Persistence,
}
