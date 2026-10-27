package com.lnight.valence.server.routes

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.http.AuthTokenRequest
import com.lnight.valence.server.domain.Login

class AuthTokenRequestMapper {
    fun toDomain(request: AuthTokenRequest): Login? =
        Parsing.orNull {
            Login(
                challenge = Base64Url.decode(request.challenge),
                mailboxId = request.mailboxId,
                signature = Base64Url.decode(request.signature),
            )
        }
}
