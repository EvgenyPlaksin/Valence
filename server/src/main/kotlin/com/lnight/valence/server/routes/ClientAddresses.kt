package com.lnight.valence.server.routes

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin

class ClientAddresses(
    private val trustProxyHeaders: Boolean,
) {
    fun of(call: ApplicationCall): String {
        val forwarded =
            if (trustProxyHeaders) {
                call.request.headers[HttpHeaders.XForwardedFor]
                    ?.split(',')
                    ?.lastOrNull()
                    ?.trim()
            } else {
                null
            }
        return forwarded?.takeIf { it.isNotEmpty() } ?: call.request.origin.remoteAddress
    }
}
