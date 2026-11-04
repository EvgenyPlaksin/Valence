package com.lnight.valence.server

import com.lnight.valence.protocol.HealthResponse
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.server.crypto.HmacChallengeAuthenticator
import com.lnight.valence.server.crypto.JwtAccessTokenIssuer
import com.lnight.valence.server.crypto.JwtAccessTokenVerifier
import com.lnight.valence.server.crypto.LibsignalPublicKeyVerifier
import com.lnight.valence.server.crypto.SecureRandomBytes
import com.lnight.valence.server.domain.ChallengeService
import com.lnight.valence.server.domain.KeyManagementService
import com.lnight.valence.server.domain.LoginService
import com.lnight.valence.server.domain.RegistrationService
import com.lnight.valence.server.routes.AuthTokenRequestMapper
import com.lnight.valence.server.routes.RegisterRequestMapper
import com.lnight.valence.server.routes.ReplaceSignedPreKeysRequestMapper
import com.lnight.valence.server.routes.UploadOneTimePreKeysRequestMapper
import com.lnight.valence.server.routes.accessToken
import com.lnight.valence.server.routes.authRoutes
import com.lnight.valence.server.routes.keyRoutes
import com.lnight.valence.server.routes.loginRoutes
import com.lnight.valence.server.routes.registrationRoutes
import com.lnight.valence.server.store.InMemoryMailboxStore
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
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
    val services = createServices()
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(services) }
        .start(wait = true)
}

private fun createServices(): ServerServices {
    val clock = Clock.systemUTC()
    val challenges =
        ChallengeService(
            clock = clock,
            random = SecureRandomBytes(),
            authenticator = HmacChallengeAuthenticator(ServerConfig.challengeKey()),
        )
    val keyVerifier = LibsignalPublicKeyVerifier()
    val store = InMemoryMailboxStore()
    val tokenKey = ServerConfig.tokenKey()
    val tokens = JwtAccessTokenIssuer(clock, tokenKey)
    return ServerServices(
        challenges = challenges,
        registration = RegistrationService(challenges, keyVerifier, store, tokens, clock),
        login = LoginService(challenges, keyVerifier, store, tokens),
        keys = KeyManagementService(keyVerifier, store),
        tokenVerifier = JwtAccessTokenVerifier(clock, tokenKey),
    )
}

fun Application.module(services: ServerServices) {
    install(ContentNegotiation) {
        json(ProtocolJson.json)
    }
    install(Authentication) {
        accessToken(services.tokenVerifier)
    }
    routing {
        get("/health") {
            call.respond(HealthResponse())
        }
        authRoutes(services.challenges)
        registrationRoutes(services.registration, RegisterRequestMapper())
        loginRoutes(services.login, AuthTokenRequestMapper())
        keyRoutes(services.keys, ReplaceSignedPreKeysRequestMapper(), UploadOneTimePreKeysRequestMapper())
    }
}
