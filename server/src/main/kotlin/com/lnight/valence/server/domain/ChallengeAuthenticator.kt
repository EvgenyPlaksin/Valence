package com.lnight.valence.server.domain

fun interface ChallengeAuthenticator {
    fun sign(data: ByteArray): ByteArray
}
