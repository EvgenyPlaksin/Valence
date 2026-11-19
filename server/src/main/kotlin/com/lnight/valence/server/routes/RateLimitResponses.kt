package com.lnight.valence.server.routes

import com.lnight.valence.protocol.ProtocolHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond

private const val MILLIS_PER_SECOND = 1_000L

internal suspend fun ApplicationCall.respondRateLimited(retryAfterMillis: Long) {
    val seconds = maxOf(1L, (retryAfterMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND)
    response.header(ProtocolHeaders.RETRY_AFTER, seconds.toString())
    respond(HttpStatusCode.TooManyRequests)
}
