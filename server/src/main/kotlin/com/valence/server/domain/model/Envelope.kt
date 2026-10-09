package com.valence.server.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class EnvelopeType {
    HANDSHAKE,
    ENCRYPTED_MESSAGE,
    DELIVERY_RECEIPT
}

@Serializable
data class Envelope(
    val id: String,
    val senderId: String,
    val recipientId: String,
    val type: EnvelopeType,
    val payload: String,
    val timestamp: Long
)
