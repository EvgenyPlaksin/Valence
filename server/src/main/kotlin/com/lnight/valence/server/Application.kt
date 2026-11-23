package com.lnight.valence.server

import com.lnight.valence.protocol.HealthResponse
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.server.crypto.HmacChallengeAuthenticator
import com.lnight.valence.server.crypto.JwtAccessTokenIssuer
import com.lnight.valence.server.crypto.JwtAccessTokenVerifier
import com.lnight.valence.server.crypto.LibsignalPublicKeyVerifier
import com.lnight.valence.server.crypto.LibsignalSenderCertificateIssuer
import com.lnight.valence.server.crypto.SecureRandomBytes
import com.lnight.valence.server.domain.ChallengeService
import com.lnight.valence.server.domain.DeliveryKeyAuthenticator
import com.lnight.valence.server.domain.InboxPurger
import com.lnight.valence.server.domain.KeyManagementService
import com.lnight.valence.server.domain.LoginService
import com.lnight.valence.server.domain.MailboxSubscriptions
import com.lnight.valence.server.domain.PreKeyBundleService
import com.lnight.valence.server.domain.RateLimitPolicies
import com.lnight.valence.server.domain.ReceiveService
import com.lnight.valence.server.domain.RegistrationService
import com.lnight.valence.server.domain.SendMessageService
import com.lnight.valence.server.domain.SenderCertificateService
import com.lnight.valence.server.domain.TokenBucketRateLimiter
import com.lnight.valence.server.routes.AuthTokenRequestMapper
import com.lnight.valence.server.routes.ClientAddresses
import com.lnight.valence.server.routes.MessageDeliverFrameMapper
import com.lnight.valence.server.routes.MetricNames
import com.lnight.valence.server.routes.PreKeyBundleResponseMapper
import com.lnight.valence.server.routes.RegisterRequestMapper
import com.lnight.valence.server.routes.ReplaceSignedPreKeysRequestMapper
import com.lnight.valence.server.routes.ServerMetrics
import com.lnight.valence.server.routes.UploadOneTimePreKeysRequestMapper
import com.lnight.valence.server.routes.accessToken
import com.lnight.valence.server.routes.authRoutes
import com.lnight.valence.server.routes.ipRateLimiting
import com.lnight.valence.server.routes.keyRoutes
import com.lnight.valence.server.routes.loginRoutes
import com.lnight.valence.server.routes.messageRoutes
import com.lnight.valence.server.routes.metricsRecording
import com.lnight.valence.server.routes.metricsRoutes
import com.lnight.valence.server.routes.preKeyRoutes
import com.lnight.valence.server.routes.registrationRoutes
import com.lnight.valence.server.routes.senderCertificateRoutes
import com.lnight.valence.server.routes.socketRoutes
import com.lnight.valence.server.store.PostgresDatabase
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_PORT = 8081
private const val PING_PERIOD_SECONDS = 20L
private const val IDLE_TIMEOUT_SECONDS = 40L
private const val MAX_WEBSOCKET_FRAME_BYTES = 131_072L
private const val PURGE_INTERVAL_MINUTES = 5L

fun main() {
    val database = PostgresDatabase.open(ServerConfig.databaseConfiguration())
    try {
        val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
        val services = createServices(database)
        embeddedServer(Netty, port = port, host = "0.0.0.0") { module(services) }
            .start(wait = true)
    } finally {
        database.close()
    }
}

private fun createServices(database: PostgresDatabase): ServerServices {
    val clock = Clock.systemUTC()
    val policies = RateLimitPolicies()
    val limiter = TokenBucketRateLimiter(clock)
    val challenges =
        ChallengeService(
            clock = clock,
            random = SecureRandomBytes(),
            authenticator = HmacChallengeAuthenticator(ServerConfig.challengeKey()),
        )
    val keyVerifier = LibsignalPublicKeyVerifier()
    val store = database.mailboxStore
    val inbox = database.inboxStore
    val subscriptions = MailboxSubscriptions()
    val authenticator = DeliveryKeyAuthenticator(store)
    val tokenKey = ServerConfig.tokenKey()
    val tokens = JwtAccessTokenIssuer(clock, tokenKey)
    val certificateIssuer =
        LibsignalSenderCertificateIssuer(ServerConfig.serverCertificate(), ServerConfig.certificateKey())
    return ServerServices(
        challenges = challenges,
        registration = RegistrationService(challenges, keyVerifier, store, tokens, clock),
        login = LoginService(challenges, keyVerifier, store, tokens),
        keys = KeyManagementService(keyVerifier, store),
        bundles = PreKeyBundleService(authenticator, store, limiter, policies.bundlePerMailbox),
        messages = SendMessageService(authenticator, inbox, subscriptions, limiter, policies.sendPerMailbox, clock),
        receive = ReceiveService(inbox, clock, policies.socketFrames),
        subscriptions = subscriptions,
        certificates = SenderCertificateService(store, certificateIssuer, clock),
        tokenVerifier = JwtAccessTokenVerifier(clock, tokenKey),
        rateLimiter = limiter,
        rateLimits = policies,
        trustProxyHeaders = ServerConfig.trustProxyHeaders(),
        purger = InboxPurger(inbox, clock),
        inbox = inbox,
        metrics = ServerMetrics(),
        metricsToken = ServerConfig.metricsToken(),
        databaseReady = database::isReady,
    )
}

fun Application.module(services: ServerServices) {
    install(ContentNegotiation) {
        json(ProtocolJson.json)
    }
    install(Authentication) {
        accessToken(services.tokenVerifier)
    }
    install(metricsRecording) {
        metrics = services.metrics
    }
    monitor.subscribe(ApplicationStopPreparing) { services.subscriptions.shutdown() }
    install(WebSockets) {
        pingPeriod = PING_PERIOD_SECONDS.seconds
        timeout = IDLE_TIMEOUT_SECONDS.seconds
        maxFrameSize = MAX_WEBSOCKET_FRAME_BYTES
    }
    install(ipRateLimiting) {
        limiter = services.rateLimiter
        policies = services.rateLimits
        addresses = ClientAddresses(services.trustProxyHeaders)
    }
    schedulePurge(services.purger, PURGE_INTERVAL_MINUTES.minutes, services.metrics)
    routing {
        get("/health") {
            call.respond(HealthResponse())
        }
        get("/ready") {
            if (services.databaseReady()) {
                call.respond(HealthResponse())
            } else {
                call.respond(io.ktor.http.HttpStatusCode.ServiceUnavailable)
            }
        }
        authRoutes(services.challenges)
        registrationRoutes(services.registration, RegisterRequestMapper())
        loginRoutes(services.login, AuthTokenRequestMapper())
        keyRoutes(services.keys, ReplaceSignedPreKeysRequestMapper(), UploadOneTimePreKeysRequestMapper())
        preKeyRoutes(services.bundles, PreKeyBundleResponseMapper())
        messageRoutes(services.messages)
        socketRoutes(services.receive, services.subscriptions, MessageDeliverFrameMapper(), services.metrics)
        senderCertificateRoutes(services.certificates)
        metricsRoutes(services.metrics, services.metricsToken) {
            mapOf(
                MetricNames.QUEUE_DEPTH to services.inbox.queueDepth().toLong(),
                MetricNames.ACTIVE_CONNECTIONS to services.subscriptions.activeCount().toLong(),
            )
        }
    }
}
