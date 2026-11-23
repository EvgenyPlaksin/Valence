package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.server.domain.AccessTokenVerifier
import com.lnight.valence.server.domain.DeliveryKeyAuthenticator
import com.lnight.valence.server.domain.InboxPurger
import com.lnight.valence.server.domain.InboxStore
import com.lnight.valence.server.domain.KeyManagementService
import com.lnight.valence.server.domain.MailboxStore
import com.lnight.valence.server.domain.MailboxSubscriptions
import com.lnight.valence.server.domain.PreKeyBundleService
import com.lnight.valence.server.domain.PublicKeyVerifier
import com.lnight.valence.server.domain.RateLimitPolicies
import com.lnight.valence.server.domain.ReceiveService
import com.lnight.valence.server.domain.SendMessageService
import com.lnight.valence.server.domain.SenderCertificateIssuer
import com.lnight.valence.server.domain.SenderCertificateService
import com.lnight.valence.server.domain.TokenBucketRateLimiter
import com.lnight.valence.server.routes.ServerMetrics
import com.lnight.valence.server.store.InMemoryInboxStore
import com.lnight.valence.server.store.InMemoryMailboxStore
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal fun InMemoryMailboxStore.seed(
    mailbox: com.lnight.valence.server.domain.Mailbox,
    keys: List<com.lnight.valence.server.domain.OneTimePreKey> = emptyList(),
): InMemoryMailboxStore = apply { runBlocking { create(mailbox, keys) } }

internal object FakeAccessTokens {
    private const val PREFIX = "token-for-"

    val verifier = AccessTokenVerifier { token -> token.removePrefix(PREFIX).takeIf { token.startsWith(PREFIX) } }

    fun bearer(mailboxId: String): String = PREFIX + mailboxId
}

internal object FakeCertificates {
    val bytes: ByteArray = Base64Url.decode("c3VwZXJjZXJ0")
    val issuer = SenderCertificateIssuer { _, _, _ -> bytes }
}

internal object TestServices {
    fun create(
        verifier: PublicKeyVerifier = FakePublicKeyVerifier(),
        store: MailboxStore = InMemoryMailboxStore(),
        inbox: InboxStore = InMemoryInboxStore(),
        atMillis: Long = ChallengeTestSupport.ISSUED_AT_MILLIS,
        policies: RateLimitPolicies = RateLimitPolicies(),
        trustProxyHeaders: Boolean = false,
        clock: TestClock = TestClock(atMillis),
        metrics: ServerMetrics = ServerMetrics(),
        metricsToken: String? = null,
    ): ServerServices {
        val subscriptions = MailboxSubscriptions()
        val limiter = TokenBucketRateLimiter(clock)
        val authenticator = DeliveryKeyAuthenticator(store)
        return ServerServices(
            challenges = ChallengeTestSupport.service(atMillis),
            registration = RegistrationTestSupport.service(verifier, store, atMillis),
            login = LoginTestSupport.service(verifier, store, atMillis),
            keys = KeyManagementService(verifier, store),
            bundles = PreKeyBundleService(authenticator, store, limiter, policies.bundlePerMailbox),
            messages = SendMessageService(authenticator, inbox, subscriptions, limiter, policies.sendPerMailbox, clock),
            receive = ReceiveService(inbox, clock, policies.socketFrames),
            subscriptions = subscriptions,
            certificates = SenderCertificateService(store, FakeCertificates.issuer, clock),
            tokenVerifier = FakeAccessTokens.verifier,
            rateLimiter = limiter,
            rateLimits = policies,
            trustProxyHeaders = trustProxyHeaders,
            purger = InboxPurger(inbox, clock),
            inbox = inbox,
            metrics = metrics,
            metricsToken = metricsToken,
        )
    }
}
