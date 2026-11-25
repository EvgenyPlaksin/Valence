package com.lnight.valence.server.routes

import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.protocol.http.KeyStatusResponse
import com.lnight.valence.protocol.http.ReplaceSignedPreKeysRequest
import com.lnight.valence.protocol.http.UpdateDeliveryKeyRequest
import com.lnight.valence.protocol.http.UploadOneTimeKyberPreKeysRequest
import com.lnight.valence.protocol.http.UploadOneTimePreKeysRequest
import com.lnight.valence.server.domain.DeliveryKeyUpdateResult
import com.lnight.valence.server.domain.KeyManagementService
import com.lnight.valence.server.domain.KeyUpdateRejection
import com.lnight.valence.server.domain.KeyUpdateResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

fun Route.keyRoutes(
    service: KeyManagementService,
    signedMapper: ReplaceSignedPreKeysRequestMapper,
    oneTimeMapper: UploadOneTimePreKeysRequestMapper,
    oneTimeKyberMapper: UploadOneTimeKyberPreKeysRequestMapper,
    deliveryKeyMapper: UpdateDeliveryKeyRequestMapper,
) {
    authenticate(ACCESS_TOKEN_AUTH) {
        get(ProtocolRoutes.KEYS_STATUS) {
            val counts = service.status(call.mailboxId)
            if (counts == null) {
                call.respond(HttpStatusCode.Unauthorized)
            } else {
                call.respond(
                    KeyStatusResponse(
                        oneTimeRemaining = counts.oneTimeRemaining,
                        oneTimeKyberRemaining = counts.oneTimeKyberRemaining,
                    ),
                )
            }
        }
        deliveryKeyRotationRoute(service, deliveryKeyMapper)
        put(ProtocolRoutes.KEYS_SIGNED) {
            val replacement =
                Parsing
                    .decode(call.receiveText(), ReplaceSignedPreKeysRequest.serializer()) { it.v }
                    ?.let(signedMapper::toDomain)
            val result = replacement?.let { service.replaceSignedPreKeys(call.mailboxId, it) } ?: malformed()
            call.respondWith(result) { _, _ -> call.respond(HttpStatusCode.NoContent) }
        }
        post(ProtocolRoutes.KEYS_ONE_TIME) {
            val keys =
                Parsing
                    .decode(call.receiveText(), UploadOneTimePreKeysRequest.serializer()) { it.v }
                    ?.let(oneTimeMapper::toDomain)
            val result = keys?.let { service.uploadOneTimePreKeys(call.mailboxId, it) } ?: malformed()
            call.respondWith(result) { ecRemaining, kyberRemaining ->
                call.respond(
                    KeyStatusResponse(
                        oneTimeRemaining = ecRemaining,
                        oneTimeKyberRemaining = kyberRemaining,
                    ),
                )
            }
        }
        post(ProtocolRoutes.KEYS_KYBER_ONE_TIME) {
            val keys =
                Parsing
                    .decode(call.receiveText(), UploadOneTimeKyberPreKeysRequest.serializer()) { it.v }
                    ?.let(oneTimeKyberMapper::toDomain)
            val result = keys?.let { service.uploadOneTimeKyberPreKeys(call.mailboxId, it) } ?: malformed()
            call.respondWith(result) { ecRemaining, kyberRemaining ->
                call.respond(
                    KeyStatusResponse(
                        oneTimeRemaining = ecRemaining,
                        oneTimeKyberRemaining = kyberRemaining,
                    ),
                )
            }
        }
    }
}

private fun Route.deliveryKeyRotationRoute(
    service: KeyManagementService,
    mapper: UpdateDeliveryKeyRequestMapper,
) {
    put(ProtocolRoutes.KEYS_DELIVERY) {
        val hash =
            Parsing
                .decode(call.receiveText(), UpdateDeliveryKeyRequest.serializer()) { it.v }
                ?.let(mapper::toDomain)
        when (val result = hash?.let { service.updateDeliveryKeyHash(call.mailboxId, it) }) {
            DeliveryKeyUpdateResult.Updated -> call.respond(HttpStatusCode.NoContent)
            DeliveryKeyUpdateResult.UnknownMailbox -> call.respond(HttpStatusCode.Unauthorized)
            DeliveryKeyUpdateResult.Malformed, null -> call.respond(HttpStatusCode.BadRequest)
        }
    }
}

private val ApplicationCall.mailboxId: String
    get() = checkNotNull(principal<MailboxPrincipal>()) { "Authenticated route without a principal" }.mailboxId

private fun malformed() = KeyUpdateResult.Rejected(KeyUpdateRejection.MALFORMED)

private suspend fun ApplicationCall.respondWith(
    result: KeyUpdateResult,
    onUpdated: suspend (Int, Int) -> Unit,
) {
    when (result) {
        is KeyUpdateResult.Updated -> onUpdated(result.oneTimeRemaining, result.oneTimeKyberRemaining)
        is KeyUpdateResult.Rejected -> respond(statusOf(result.reason))
    }
}

private fun statusOf(reason: KeyUpdateRejection): HttpStatusCode =
    when (reason) {
        KeyUpdateRejection.MALFORMED, KeyUpdateRejection.INVALID_SIGNATURE -> HttpStatusCode.BadRequest
        KeyUpdateRejection.ID_EXISTS -> HttpStatusCode.Conflict
        KeyUpdateRejection.UNKNOWN_MAILBOX -> HttpStatusCode.Unauthorized
    }
