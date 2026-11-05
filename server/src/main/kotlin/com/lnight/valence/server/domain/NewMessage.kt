package com.lnight.valence.server.domain

class NewMessage(
    val envelope: ByteArray,
    val serverTsMillis: Long,
    val expiresAtMillis: Long,
)
