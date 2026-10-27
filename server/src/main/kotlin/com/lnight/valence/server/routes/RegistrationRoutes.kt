package com.lnight.valence.server.routes

import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.protocol.ProtocolVersion
import com.lnight.valence.protocol.http.AccessTokenResponse
import com.lnight.valence.protocol.http.RegisterRequest
import com.lnight.valence.server.domain.RegistrationRejection
import com.lnight.valence.server.domain.RegistrationResult
import com.lnight.valence.server.domain.RegistrationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.registrationRoutes(
    service: RegistrationService,
    mapper: RegisterRequestMapper,
) {
    post(ProtocolRoutes.REGISTER) {
        val body = call.receiveText()
        val registration =
            Parsing
                .orNull { ProtocolJson.json.decodeFromString(RegisterRequest.serializer(), body) }
                ?.takeIf { ProtocolVersion.isSupported(it.v) }
                ?.let(mapper::toDomain)
        val result =
            registration?.let(service::register) ?: RegistrationResult.Rejected(RegistrationRejection.MALFORMED)
        call.respondWith(result)
    }
}

private suspend fun ApplicationCall.respondWith(result: RegistrationResult) {
    when (result) {
        is RegistrationResult.Registered -> {
            respond(
                HttpStatusCode.Created,
                AccessTokenResponse(accessToken = result.token.value, expiresAt = result.token.expiresAtMillis),
            )
        }

        is RegistrationResult.Rejected -> {
            respond(statusOf(result.reason))
        }
    }
}

private fun statusOf(reason: RegistrationRejection): HttpStatusCode =
    when (reason) {
        RegistrationRejection.MALFORMED -> HttpStatusCode.BadRequest

        RegistrationRejection.INVALID_CHALLENGE,
        RegistrationRejection.INVALID_PROOF,
        RegistrationRejection.INVALID_PREKEY_SIGNATURE,
        -> HttpStatusCode.Unauthorized

        RegistrationRejection.MAILBOX_EXISTS -> HttpStatusCode.Conflict
    }
