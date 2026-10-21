package com.lnight.valence.protocol.http

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.Serializable

@Serializable
data class AccessTokenResponse(
    val v: Int = ProtocolVersion.CURRENT,
    val accessToken: String,
    val expiresAt: Long,
)
