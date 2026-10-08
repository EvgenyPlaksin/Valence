package com.valence.server.model

import kotlinx.serialization.Serializable

@Serializable
data class MessagePacket(
    val senderId: String,
    val recipientId: String,
    val encryptedPayload: String,
    val timestamp: Long
)
