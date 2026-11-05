package com.lnight.valence.server.routes

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.ProtocolHeaders
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.protocol.http.SendMessageRequest
import com.lnight.valence.protocol.http.SendMessageResponse
import com.lnight.valence.server.domain.SendMessageService
import com.lnight.valence.server.domain.SendRejection
import com.lnight.valence.server.domain.SendResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

private const val MAX_BODY_BYTES = 98_304L
private const val QUOTA_RETRY_AFTER_SECONDS = 300

private class ParsedSend(
    val envelope: ByteArray,
    val ttlSeconds: Long?,
)

fun Route.messageRoutes(service: SendMessageService) {
    post(ProtocolRoutes.SEND_MESSAGE) {
        val body = call.receiveLimitedText(MAX_BODY_BYTES)
        val parsed = body?.let(::parse)
        val deliveryKey =
            call.request.headers[ProtocolHeaders.DELIVERY_KEY]?.let { Parsing.orNull { Base64Url.decode(it) } }
        when {
            body == null -> {
                call.respond(HttpStatusCode.PayloadTooLarge)
            }

            parsed == null -> {
                call.respond(HttpStatusCode.BadRequest)
            }

            deliveryKey == null -> {
                call.respond(HttpStatusCode.Unauthorized)
            }

            else -> {
                call.respondWith(
                    service.send(
                        call.parameters["to"].orEmpty(),
                        deliveryKey,
                        parsed.envelope,
                        parsed.ttlSeconds,
                    ),
                )
            }
        }
    }
}

private fun parse(body: String): ParsedSend? {
    val request = Parsing.decode(body, SendMessageRequest.serializer()) { it.v }
    val envelope = request?.let { Parsing.orNull { Base64Url.decode(it.envelope) } }
    return if (request != null && envelope != null) ParsedSend(envelope, request.ttlSeconds) else null
}

private suspend fun ApplicationCall.receiveLimitedText(limit: Long): String? {
    val declared = request.contentLength()
    val bytes =
        if (declared != null && declared > limit) null else receiveChannel().readRemaining(limit + 1).readByteArray()
    return bytes?.takeIf { it.size <= limit }?.decodeToString()
}

private suspend fun ApplicationCall.respondWith(result: SendResult) {
    when (result) {
        is SendResult.Accepted -> {
            respond(HttpStatusCode.Created, SendMessageResponse(serverTs = result.serverTsMillis))
        }

        is SendResult.Rejected -> {
            respondRejected(result.reason)
        }
    }
}

private suspend fun ApplicationCall.respondRejected(reason: SendRejection) {
    when (reason) {
        SendRejection.UNAUTHORIZED -> {
            respond(HttpStatusCode.Unauthorized)
        }

        SendRejection.INVALID_REQUEST -> {
            respond(HttpStatusCode.BadRequest)
        }

        SendRejection.TOO_LARGE -> {
            respond(HttpStatusCode.PayloadTooLarge)
        }

        SendRejection.QUOTA_EXCEEDED -> {
            response.header(ProtocolHeaders.RETRY_AFTER, QUOTA_RETRY_AFTER_SECONDS.toString())
            respond(HttpStatusCode.TooManyRequests)
        }
    }
}
