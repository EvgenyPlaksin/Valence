package com.lnight.valence.protocol.http

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.Serializable

@Serializable
data class SenderCertificateResponse(
    val v: Int = ProtocolVersion.CURRENT,
    val certificate: String,
    val expiresAt: Long,
)
