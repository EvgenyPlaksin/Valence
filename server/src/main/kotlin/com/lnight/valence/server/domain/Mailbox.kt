package com.lnight.valence.server.domain

class Mailbox(
    val id: String,
    val identityKey: ByteArray,
    val signedPreKey: SignedPublicKey,
    val kyberPreKey: SignedPublicKey,
    val deliveryKeyHash: ByteArray,
    val createdAtMillis: Long,
)
