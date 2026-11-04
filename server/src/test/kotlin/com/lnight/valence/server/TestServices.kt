package com.lnight.valence.server

import com.lnight.valence.server.domain.AccessTokenVerifier
import com.lnight.valence.server.domain.KeyManagementService
import com.lnight.valence.server.domain.MailboxStore
import com.lnight.valence.server.domain.PublicKeyVerifier
import com.lnight.valence.server.store.InMemoryMailboxStore

internal object FakeAccessTokens {
    private const val PREFIX = "token-for-"

    val verifier = AccessTokenVerifier { token -> token.removePrefix(PREFIX).takeIf { token.startsWith(PREFIX) } }

    fun bearer(mailboxId: String): String = PREFIX + mailboxId
}

internal object TestServices {
    fun create(
        verifier: PublicKeyVerifier = FakePublicKeyVerifier(),
        store: MailboxStore = InMemoryMailboxStore(),
        atMillis: Long = ChallengeTestSupport.ISSUED_AT_MILLIS,
    ): ServerServices =
        ServerServices(
            challenges = ChallengeTestSupport.service(atMillis),
            registration = RegistrationTestSupport.service(verifier, store, atMillis),
            login = LoginTestSupport.service(verifier, store, atMillis),
            keys = KeyManagementService(verifier, store),
            tokenVerifier = FakeAccessTokens.verifier,
        )
}
