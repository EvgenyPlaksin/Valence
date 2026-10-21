package com.lnight.valence.protocol.http

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.Serializable

@Serializable
data class AuthTokenRequest(
    val v: Int = ProtocolVersion.CURRENT,
    val mailboxId: String,
    val challenge: String,
    val signature: String,
)
