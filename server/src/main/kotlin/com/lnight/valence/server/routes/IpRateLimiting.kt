package com.lnight.valence.server.routes

import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.server.domain.RateLimitDecision
import com.lnight.valence.server.domain.RateLimitPolicies
import com.lnight.valence.server.domain.RateLimitPolicy
import com.lnight.valence.server.domain.RateLimiter
import io.ktor.http.HttpMethod
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

private const val HEALTH_PATH = "/health"
private const val MESSAGES_PREFIX = "/v1/messages/"
private const val PREKEYS_PREFIX = "/v1/prekeys/"

class IpRateLimitConfig {
    lateinit var limiter: RateLimiter
    lateinit var policies: RateLimitPolicies
    var addresses: ClientAddresses = ClientAddresses(trustProxyHeaders = false)
}

val ipRateLimiting =
    createApplicationPlugin(name = "IpRateLimiting", createConfiguration = ::IpRateLimitConfig) {
        val limiter = pluginConfig.limiter
        val policies = pluginConfig.policies
        val addresses = pluginConfig.addresses
        onCall { call ->
            val path = call.request.path()
            if (path != HEALTH_PATH) {
                val (scope, policy) = classify(call.request.httpMethod, path, policies)
                val decision = limiter.tryAcquire("$scope:address:${addresses.of(call)}", policy)
                if (decision is RateLimitDecision.Limited) call.respondRateLimited(decision.retryAfterMillis)
            }
        }
    }

private fun classify(
    method: HttpMethod,
    path: String,
    policies: RateLimitPolicies,
): Pair<String, RateLimitPolicy> =
    when {
        method == HttpMethod.Post && path == ProtocolRoutes.AUTH_CHALLENGE -> {
            "challenge" to
                policies.challengePerAddress
        }

        method == HttpMethod.Post && path == ProtocolRoutes.REGISTER -> {
            "register" to policies.registerPerAddress
        }

        method == HttpMethod.Post && path == ProtocolRoutes.AUTH_TOKEN -> {
            "login" to policies.loginPerAddress
        }

        method == HttpMethod.Get && path.startsWith(PREKEYS_PREFIX) -> {
            "bundle" to policies.bundlePerAddress
        }

        method == HttpMethod.Post && path.startsWith(MESSAGES_PREFIX) -> {
            "send" to policies.sendPerAddress
        }

        else -> {
            "other" to policies.otherPerAddress
        }
    }
