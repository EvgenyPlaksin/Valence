package com.lnight.valence.server.routes

import com.lnight.valence.server.domain.AccessTokenVerifier
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.bearer

const val ACCESS_TOKEN_AUTH = "access-token"

fun AuthenticationConfig.accessToken(verifier: AccessTokenVerifier) {
    bearer(ACCESS_TOKEN_AUTH) {
        authenticate { credential -> verifier.verify(credential.token)?.let(::MailboxPrincipal) }
    }
}
