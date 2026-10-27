package com.lnight.valence.server.domain

class SignedPublicKey(
    val id: Int,
    val publicKey: ByteArray,
    val signature: ByteArray,
)

class OneTimePreKey(
    val id: Int,
    val publicKey: ByteArray,
)
