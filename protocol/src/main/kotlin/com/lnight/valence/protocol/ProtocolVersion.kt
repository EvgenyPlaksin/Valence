package com.lnight.valence.protocol

object ProtocolVersion {
    const val CURRENT = 1
    const val MINIMUM_SUPPORTED = 1

    fun isSupported(version: Int): Boolean = version in MINIMUM_SUPPORTED..CURRENT
}