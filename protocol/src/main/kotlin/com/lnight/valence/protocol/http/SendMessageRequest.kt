package com.lnight.valence.protocol.http

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.Serializable

@Serializable
data class SendMessageRequest(
    val v: Int = ProtocolVersion.CURRENT,
    val envelope: String,
    val ttlSeconds: Long? = null,
)