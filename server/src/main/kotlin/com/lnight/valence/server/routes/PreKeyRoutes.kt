package com.lnight.valence.server.routes

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.ProtocolHeaders
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.server.domain.BundleResult
import com.lnight.valence.server.domain.PreKeyBundleService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.preKeyRoutes(
    service: PreKeyBundleService,
    mapper: PreKeyBundleResponseMapper,
) {
    get(ProtocolRoutes.PRE_KEYS) {
        val mailboxId = call.parameters["mailboxId"].orEmpty()
        val deliveryKey =
            call.request.headers[ProtocolHeaders.DELIVERY_KEY]?.let { Parsing.orNull { Base64Url.decode(it) } }
        when (val result = deliveryKey?.let { service.fetch(mailboxId, it) } ?: BundleResult.Unauthorized) {
            is BundleResult.Found -> call.respond(mapper.toResponse(result.bundle))
            is BundleResult.RateLimited -> call.respondRateLimited(result.retryAfterMillis)
            BundleResult.Unauthorized -> call.respond(HttpStatusCode.Unauthorized)
        }
    }
}
