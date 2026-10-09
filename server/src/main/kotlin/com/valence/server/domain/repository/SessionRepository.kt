package com.valence.server.domain.repository

import com.valence.server.domain.model.Envelope

interface SessionRepository {
    fun registerSession(userId: String, session: Any)
    fun unregisterSession(userId: String)
    fun isUserOnline(userId: String): Boolean
    suspend fun sendEnvelope(recipientId: String, envelope: Envelope): Boolean
}
