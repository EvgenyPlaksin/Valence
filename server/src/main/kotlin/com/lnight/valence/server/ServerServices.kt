package com.lnight.valence.server

import com.lnight.valence.server.domain.AccessTokenVerifier
import com.lnight.valence.server.domain.ChallengeService
import com.lnight.valence.server.domain.InboxPurger
import com.lnight.valence.server.domain.InboxStore
import com.lnight.valence.server.domain.KeyManagementService
import com.lnight.valence.server.domain.LoginService
import com.lnight.valence.server.domain.MailboxSubscriptions
import com.lnight.valence.server.domain.PreKeyBundleService
import com.lnight.valence.server.domain.RateLimitPolicies
import com.lnight.valence.server.domain.RateLimiter
import com.lnight.valence.server.domain.ReceiveService
import com.lnight.valence.server.domain.RegistrationService
import com.lnight.valence.server.domain.SendMessageService
import com.lnight.valence.server.domain.SenderCertificateService
import com.lnight.valence.server.routes.ServerMetrics

data class ServerServices(
    val challenges: ChallengeService,
    val registration: RegistrationService,
    val login: LoginService,
    val keys: KeyManagementService,
    val bundles: PreKeyBundleService,
    val messages: SendMessageService,
    val receive: ReceiveService,
    val subscriptions: MailboxSubscriptions,
    val certificates: SenderCertificateService,
    val tokenVerifier: AccessTokenVerifier,
    val rateLimiter: RateLimiter,
    val rateLimits: RateLimitPolicies,
    val trustProxyHeaders: Boolean,
    val purger: InboxPurger,
    val inbox: InboxStore,
    val metrics: ServerMetrics,
    val metricsToken: String?,
)
