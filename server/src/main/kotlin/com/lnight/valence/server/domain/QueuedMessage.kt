package com.lnight.valence.server.domain

class QueuedMessage(
    val serverSeq: Long,
    val serverTsMillis: Long,
    val envelope: ByteArray,
    val expiresAtMillis: Long,
)
