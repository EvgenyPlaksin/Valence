package com.lnight.valence.protocol.http

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.Serializable

@Serializable
data class UploadOneTimeKyberPreKeysRequest(
    val v: Int = ProtocolVersion.CURRENT,
    val oneTimeKyberPreKeys: List<KyberPreKeyDto>,
)
