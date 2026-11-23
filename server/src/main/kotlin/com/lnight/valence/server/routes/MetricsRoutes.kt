package com.lnight.valence.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.security.MessageDigest

internal const val METRICS_PATH = "/metrics"
private const val BEARER_PREFIX = "Bearer "

fun Route.metricsRoutes(
    metrics: ServerMetrics,
    token: String?,
    gauges: () -> Map<String, Long>,
) {
    if (token != null) {
        val expected = token.toByteArray(Charsets.UTF_8)
        get(METRICS_PATH) {
            val presented =
                call.request.headers[HttpHeaders.Authorization]
                    ?.takeIf { it.startsWith(BEARER_PREFIX) }
                    ?.removePrefix(BEARER_PREFIX)
            if (presented != null && MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), expected)) {
                call.respondText(metrics.render(gauges()), ContentType.Text.Plain)
            } else {
                call.respond(HttpStatusCode.Unauthorized)
            }
        }
    }
}
