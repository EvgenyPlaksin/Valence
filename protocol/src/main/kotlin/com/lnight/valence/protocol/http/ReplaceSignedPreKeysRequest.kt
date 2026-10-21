package com.lnight.valence.protocol.http

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.Serializable

@Serializable
data class ReplaceSignedPreKeysRequest(
    val v: Int = ProtocolVersion.CURRENT,
    val signedPreKey: SignedPreKeyDto,
    val kyberPreKey: KyberPreKeyDto,
)
