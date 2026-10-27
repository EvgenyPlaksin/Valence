package com.lnight.valence.server.routes

import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.protocol.ProtocolVersion
import com.lnight.valence.protocol.http.AccessTokenResponse
import com.lnight.valence.protocol.http.AuthTokenRequest
import com.lnight.valence.server.domain.LoginRejection
import com.lnight.valence.server.domain.LoginResult
import com.lnight.valence.server.domain.LoginService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.loginRoutes(
    service: LoginService,
    mapper: AuthTokenRequestMapper,
) {
    post(ProtocolRoutes.AUTH_TOKEN) {
        val body = call.receiveText()
        val login =
            Parsing
                .orNull { ProtocolJson.json.decodeFromString(AuthTokenRequest.serializer(), body) }
                ?.takeIf { ProtocolVersion.isSupported(it.v) }
                ?.let(mapper::toDomain)
        val result = login?.let(service::login) ?: LoginResult.Rejected(LoginRejection.MALFORMED)
        call.respondWith(result)
    }
}

private suspend fun ApplicationCall.respondWith(result: LoginResult) {
    when (result) {
        is LoginResult.LoggedIn -> {
            respond(AccessTokenResponse(accessToken = result.token.value, expiresAt = result.token.expiresAtMillis))
        }

        is LoginResult.Rejected -> {
            respond(statusOf(result.reason))
        }
    }
}

private fun statusOf(reason: LoginRejection): HttpStatusCode =
    when (reason) {
        LoginRejection.MALFORMED -> HttpStatusCode.BadRequest
        LoginRejection.INVALID_CHALLENGE, LoginRejection.INVALID_CREDENTIALS -> HttpStatusCode.Unauthorized
    }
