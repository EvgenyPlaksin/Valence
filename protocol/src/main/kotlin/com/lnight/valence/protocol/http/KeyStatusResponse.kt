package com.lnight.valence.protocol.http

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.Serializable

@Serializable
data class KeyStatusResponse(
    val v: Int = ProtocolVersion.CURRENT,
    val oneTimeRemaining: Int,
)
