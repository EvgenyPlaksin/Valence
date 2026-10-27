package com.lnight.valence.server

import com.lnight.valence.protocol.HealthResponse
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.server.crypto.HmacChallengeAuthenticator
import com.lnight.valence.server.crypto.JwtAccessTokenIssuer
import com.lnight.valence.server.crypto.LibsignalPublicKeyVerifier
import com.lnight.valence.server.crypto.SecureRandomBytes
import com.lnight.valence.server.domain.ChallengeService
import com.lnight.valence.server.domain.RegistrationService
import com.lnight.valence.server.routes.RegisterRequestMapper
import com.lnight.valence.server.routes.authRoutes
import com.lnight.valence.server.routes.registrationRoutes
import com.lnight.valence.server.store.InMemoryMailboxStore
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.Clock

private const val DEFAULT_PORT = 8081

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val clock = Clock.systemUTC()
    val challengeService =
        ChallengeService(
            clock = clock,
            random = SecureRandomBytes(),
            authenticator = HmacChallengeAuthenticator(ServerConfig.challengeKey()),
        )
    val registrationService =
        RegistrationService(
            challenges = challengeService,
            keyVerifier = LibsignalPublicKeyVerifier(),
            store = InMemoryMailboxStore(),
            tokens = JwtAccessTokenIssuer(clock, ServerConfig.tokenKey()),
            clock = clock,
        )
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(challengeService, registrationService) }
        .start(wait = true)
}

fun Application.module(
    challengeService: ChallengeService,
    registrationService: RegistrationService,
) {
    install(ContentNegotiation) {
        json(ProtocolJson.json)
    }
    routing {
        get("/health") {
            call.respond(HealthResponse())
        }
        authRoutes(challengeService)
        registrationRoutes(registrationService, RegisterRequestMapper())
    }
}
