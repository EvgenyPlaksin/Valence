package com.lnight.valence.server

import com.lnight.valence.server.RegistrationTestSupport.MAILBOX_ID
import com.lnight.valence.server.domain.AccessToken
import com.lnight.valence.server.domain.AccessTokenIssuer
import com.lnight.valence.server.domain.Login
import com.lnight.valence.server.domain.LoginService
import com.lnight.valence.server.domain.MailboxStore
import com.lnight.valence.server.domain.PublicKeyVerifier

internal object LoginTestSupport {
    fun service(
        verifier: PublicKeyVerifier = FakePublicKeyVerifier(),
        store: MailboxStore = RecordingMailboxStore(),
        atMillis: Long = ChallengeTestSupport.ISSUED_AT_MILLIS,
    ): LoginService =
        LoginService(
            challenges = ChallengeTestSupport.service(atMillis),
            keyVerifier = verifier,
            store = store,
            tokens =
                AccessTokenIssuer { mailboxId ->
                    AccessToken("token-for-$mailboxId", RegistrationTestSupport.TOKEN_EXPIRY_MILLIS)
                },
        )

    fun login(
        challenge: ByteArray = ChallengeTestSupport.service().issue().bytes,
        mailboxId: String = MAILBOX_ID,
        signature: ByteArray = ByteArray(64) { 1 },
    ): Login = Login(challenge, mailboxId, signature)
}
