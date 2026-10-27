package com.lnight.valence.server.domain

class Registration(
    val challenge: ByteArray,
    val mailboxId: String,
    val identityKey: ByteArray,
    val signedPreKey: SignedPublicKey,
    val kyberPreKey: SignedPublicKey,
    val oneTimePreKeys: List<OneTimePreKey>,
    val deliveryKeyHash: ByteArray,
    val proof: ByteArray,
)
