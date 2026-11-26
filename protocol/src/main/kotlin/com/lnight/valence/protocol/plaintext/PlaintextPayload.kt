package com.lnight.valence.protocol.plaintext

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.Serializable

@Serializable
data class PlaintextPayload(
    val v: Int = ProtocolVersion.CURRENT,
    val kind: String,
    val messageId: String,
    val sentAt: Long,
    val conversationId: String,
    val body: String? = null,
    val replyTo: String? = null,
    val attachments: List<AttachmentRef> = emptyList(),
    val padding: String? = null,
)

@Serializable
data class AttachmentRef(
    val blobId: String,
    val key: String,
    val contentType: String,
    val sizeBytes: Long,
    val fileName: String? = null,
)
