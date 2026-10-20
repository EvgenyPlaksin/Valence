package com.lnight.valence.protocol.http

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.Serializable

@Serializable
data class PreKeyBundleResponse(
    val v: Int = ProtocolVersion.CURRENT,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val kyberPreKey: KyberPreKeyDto,
    val oneTimePreKey: OneTimePreKeyDto? = null,
)

@Serializable
data class SignedPreKeyDto(
    val id: Int,
    val pub: String,
    val sig: String,
)

@Serializable
data class KyberPreKeyDto(
    val id: Int,
    val pub: String,
    val sig: String,
)

@Serializable
data class OneTimePreKeyDto(
    val id: Int,
    val pub: String,
)