package com.lnight.valence.server

import com.lnight.valence.protocol.HealthResponse
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.server.crypto.HmacChallengeAuthenticator
import com.lnight.valence.server.crypto.JwtAccessTokenIssuer
import com.lnight.valence.server.crypto.JwtAccessTokenVerifier
import com.lnight.valence.server.crypto.LibsignalPublicKeyVerifier
import com.lnight.valence.server.crypto.SecureRandomBytes
import com.lnight.valence.server.domain.ChallengeService
import com.lnight.valence.server.domain.DeliveryKeyAuthenticator
import com.lnight.valence.server.domain.KeyManagementService
import com.lnight.valence.server.domain.LoginService
import com.lnight.valence.server.domain.MailboxSubscriptions
import com.lnight.valence.server.domain.PreKeyBundleService
import com.lnight.valence.server.domain.ReceiveService
import com.lnight.valence.server.domain.RegistrationService
import com.lnight.valence.server.domain.SendMessageService
import com.lnight.valence.server.routes.AuthTokenRequestMapper
import com.lnight.valence.server.routes.MessageDeliverFrameMapper
import com.lnight.valence.server.routes.PreKeyBundleResponseMapper
import com.lnight.valence.server.routes.RegisterRequestMapper
import com.lnight.valence.server.routes.ReplaceSignedPreKeysRequestMapper
import com.lnight.valence.server.routes.UploadOneTimePreKeysRequestMapper
import com.lnight.valence.server.routes.accessToken
import com.lnight.valence.server.routes.authRoutes
import com.lnight.valence.server.routes.keyRoutes
import com.lnight.valence.server.routes.loginRoutes
import com.lnight.valence.server.routes.messageRoutes
import com.lnight.valence.server.routes.preKeyRoutes
import com.lnight.valence.server.routes.registrationRoutes
import com.lnight.valence.server.routes.socketRoutes
import com.lnight.valence.server.store.InMemoryInboxStore
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
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import java.time.Clock
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_PORT = 8081
private const val PING_PERIOD_SECONDS = 20L
private const val IDLE_TIMEOUT_SECONDS = 40L
private const val MAX_WEBSOCKET_FRAME_BYTES = 131_072L

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
    val inbox = InMemoryInboxStore()
    val subscriptions = MailboxSubscriptions()
    val tokenKey = ServerConfig.tokenKey()
    val tokens = JwtAccessTokenIssuer(clock, tokenKey)
    return ServerServices(
        challenges = challenges,
        registration = RegistrationService(challenges, keyVerifier, store, tokens, clock),
        login = LoginService(challenges, keyVerifier, store, tokens),
        keys = KeyManagementService(keyVerifier, store),
        bundles = PreKeyBundleService(DeliveryKeyAuthenticator(store), store),
        messages = SendMessageService(DeliveryKeyAuthenticator(store), inbox, subscriptions, clock),
        receive = ReceiveService(inbox, clock),
        subscriptions = subscriptions,
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
    install(WebSockets) {
        pingPeriod = PING_PERIOD_SECONDS.seconds
        timeout = IDLE_TIMEOUT_SECONDS.seconds
        maxFrameSize = MAX_WEBSOCKET_FRAME_BYTES
    }
    routing {
        get("/health") {
            call.respond(HealthResponse())
        }
        authRoutes(services.challenges)
        registrationRoutes(services.registration, RegisterRequestMapper())
        loginRoutes(services.login, AuthTokenRequestMapper())
        keyRoutes(services.keys, ReplaceSignedPreKeysRequestMapper(), UploadOneTimePreKeysRequestMapper())
        preKeyRoutes(services.bundles, PreKeyBundleResponseMapper())
        messageRoutes(services.messages)
        socketRoutes(services.receive, services.subscriptions, MessageDeliverFrameMapper())
    }
}
