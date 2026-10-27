package com.lnight.valence.server.domain

sealed interface LoginResult {
    data class LoggedIn(
        val token: AccessToken,
    ) : LoginResult

    data class Rejected(
        val reason: LoginRejection,
    ) : LoginResult
}

enum class LoginRejection {
    MALFORMED,
    INVALID_CHALLENGE,
    INVALID_CREDENTIALS,
}
