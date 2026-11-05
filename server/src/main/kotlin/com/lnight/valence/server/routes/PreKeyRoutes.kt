package com.lnight.valence.server.routes

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.ProtocolHeaders
import com.lnight.valence.protocol.ProtocolRoutes
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
        val bundle = deliveryKey?.let { service.fetch(mailboxId, it) }
        if (bundle == null) {
            call.respond(HttpStatusCode.Unauthorized)
        } else {
            call.respond(mapper.toResponse(bundle))
        }
    }
}
