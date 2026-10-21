package com.lnight.valence.server.routes

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.protocol.http.AuthChallengeResponse
import com.lnight.valence.server.domain.ChallengeService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

fun Route.authRoutes(challengeService: ChallengeService) {
    post(ProtocolRoutes.AUTH_CHALLENGE) {
        val challenge = challengeService.issue()
        call.respond(
            AuthChallengeResponse(
                challenge = Base64Url.encode(challenge.bytes),
                expiresAt = challenge.expiresAtMillis,
            ),
        )
    }
}
