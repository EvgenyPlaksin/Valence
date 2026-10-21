package com.lnight.valence.protocol.http

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.Serializable

@Serializable
data class AuthChallengeResponse(
    val v: Int = ProtocolVersion.CURRENT,
    val challenge: String,
    val expiresAt: Long,
)
