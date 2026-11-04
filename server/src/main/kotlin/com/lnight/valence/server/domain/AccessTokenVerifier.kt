package com.lnight.valence.server.domain

fun interface AccessTokenVerifier {
    fun verify(token: String): String?
}
