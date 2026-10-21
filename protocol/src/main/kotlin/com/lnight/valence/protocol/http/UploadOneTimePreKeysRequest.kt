package com.lnight.valence.protocol.http

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.Serializable

@Serializable
data class UploadOneTimePreKeysRequest(
    val v: Int = ProtocolVersion.CURRENT,
    val oneTimePreKeys: List<OneTimePreKeyDto>,
)
