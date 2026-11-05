package com.lnight.valence.server.domain

class PreKeyBundle(
    val identityKey: ByteArray,
    val signedPreKey: SignedPublicKey,
    val kyberPreKey: SignedPublicKey,
    val oneTimePreKey: OneTimePreKey?,
)
