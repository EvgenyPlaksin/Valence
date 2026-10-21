package com.lnight.valence.server.domain

fun interface RandomBytes {
    fun next(size: Int): ByteArray
}
