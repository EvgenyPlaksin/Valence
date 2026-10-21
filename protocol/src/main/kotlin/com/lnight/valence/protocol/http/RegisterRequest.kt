package com.lnight.valence.protocol.http

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val v: Int = ProtocolVersion.CURRENT,
    val challenge: String,
    val mailboxId: String,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val kyberPreKey: KyberPreKeyDto,
    val oneTimePreKeys: List<OneTimePreKeyDto>,
    val deliveryKeyHash: String,
    val proof: String,
)
