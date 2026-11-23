package com.lnight.valence.server.routes

import com.lnight.valence.protocol.ProtocolRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.path

private const val HEALTH_PATH = "/health"
private const val MESSAGES_PREFIX = "/v1/messages/"
private const val PREKEYS_PREFIX = "/v1/prekeys/"
private const val KEYS_PREFIX = "/v1/keys/"

class MetricsRecordingConfig {
    lateinit var metrics: ServerMetrics
}

val metricsRecording =
    createApplicationPlugin(name = "MetricsRecording", createConfiguration = ::MetricsRecordingConfig) {
        val metrics = pluginConfig.metrics
        on(ResponseSent) { call ->
            val status = call.response.status()?.value ?: HttpStatusCode.OK.value
            val labels = mapOf("scope" to scopeOf(call.request.path()), "status" to status.toString())
            metrics.increment(MetricNames.HTTP_RESPONSES, labels)
        }
    }

internal fun scopeOf(path: String): String =
    when {
        path == ProtocolRoutes.AUTH_CHALLENGE -> "challenge"
        path == ProtocolRoutes.REGISTER -> "register"
        path == ProtocolRoutes.AUTH_TOKEN -> "login"
        path.startsWith(PREKEYS_PREFIX) -> "bundle"
        path.startsWith(MESSAGES_PREFIX) -> "send"
        path.startsWith(KEYS_PREFIX) -> "keys"
        path == ProtocolRoutes.SENDER_CERTIFICATE -> "certificate"
        path == ProtocolRoutes.SOCKET -> "socket"
        path == METRICS_PATH -> "metrics"
        path == HEALTH_PATH -> "health"
        else -> "other"
    }
