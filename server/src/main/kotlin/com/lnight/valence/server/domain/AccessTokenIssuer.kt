package com.lnight.valence.server.domain

fun interface AccessTokenIssuer {
    fun issue(mailboxId: String): AccessToken
}
