package com.lnight.valence.server.domain

class Login(
    val challenge: ByteArray,
    val mailboxId: String,
    val signature: ByteArray,
)
