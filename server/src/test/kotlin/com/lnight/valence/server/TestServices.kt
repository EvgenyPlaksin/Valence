package com.lnight.valence.server

import com.lnight.valence.server.domain.AccessTokenVerifier
import com.lnight.valence.server.domain.DeliveryKeyAuthenticator
import com.lnight.valence.server.domain.InboxStore
import com.lnight.valence.server.domain.KeyManagementService
import com.lnight.valence.server.domain.MailboxStore
import com.lnight.valence.server.domain.MailboxSubscriptions
import com.lnight.valence.server.domain.PreKeyBundleService
import com.lnight.valence.server.domain.PublicKeyVerifier
import com.lnight.valence.server.domain.ReceiveService
import com.lnight.valence.server.domain.SendMessageService
import com.lnight.valence.server.store.InMemoryInboxStore
import com.lnight.valence.server.store.InMemoryMailboxStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal object FakeAccessTokens {
    private const val PREFIX = "token-for-"

    val verifier = AccessTokenVerifier { token -> token.removePrefix(PREFIX).takeIf { token.startsWith(PREFIX) } }

    fun bearer(mailboxId: String): String = PREFIX + mailboxId
}

internal object TestServices {
    fun create(
        verifier: PublicKeyVerifier = FakePublicKeyVerifier(),
        store: MailboxStore = InMemoryMailboxStore(),
        inbox: InboxStore = InMemoryInboxStore(),
        atMillis: Long = ChallengeTestSupport.ISSUED_AT_MILLIS,
    ): ServerServices {
        val clock = Clock.fixed(Instant.ofEpochMilli(atMillis), ZoneOffset.UTC)
        val subscriptions = MailboxSubscriptions()
        return ServerServices(
            challenges = ChallengeTestSupport.service(atMillis),
            registration = RegistrationTestSupport.service(verifier, store, atMillis),
            login = LoginTestSupport.service(verifier, store, atMillis),
            keys = KeyManagementService(verifier, store),
            bundles = PreKeyBundleService(DeliveryKeyAuthenticator(store), store),
            messages = SendMessageService(DeliveryKeyAuthenticator(store), inbox, subscriptions, clock),
            receive = ReceiveService(inbox, clock),
            subscriptions = subscriptions,
            tokenVerifier = FakeAccessTokens.verifier,
        )
    }
}
