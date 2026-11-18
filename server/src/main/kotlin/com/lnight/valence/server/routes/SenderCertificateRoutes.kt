package com.lnight.valence.server.routes

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.protocol.http.SenderCertificateResponse
import com.lnight.valence.server.domain.SenderCertificateService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.senderCertificateRoutes(service: SenderCertificateService) {
    authenticate(ACCESS_TOKEN_AUTH) {
        get(ProtocolRoutes.SENDER_CERTIFICATE) {
            val certificate = service.issue(call.mailboxId)
            if (certificate == null) {
                call.respond(HttpStatusCode.Unauthorized)
            } else {
                call.respond(
                    SenderCertificateResponse(
                        certificate = Base64Url.encode(certificate.bytes),
                        expiresAt = certificate.expiresAtMillis,
                    ),
                )
            }
        }
    }
}

private val ApplicationCall.mailboxId: String
    get() = checkNotNull(principal<MailboxPrincipal>()) { "Authenticated route without a principal" }.mailboxId
