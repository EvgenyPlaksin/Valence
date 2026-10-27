package com.lnight.valence.server

import com.lnight.valence.protocol.SignedMessages
import com.lnight.valence.server.ChallengeTestSupport.ISSUED_AT_MILLIS
import com.lnight.valence.server.ChallengeTestSupport.LIFETIME_MILLIS
import com.lnight.valence.server.RegistrationTestSupport.MAILBOX_ID
import com.lnight.valence.server.domain.Login
import com.lnight.valence.server.domain.LoginRejection
import com.lnight.valence.server.domain.LoginResult
import com.lnight.valence.server.domain.LoginService
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LoginServiceTest {
    private fun storeWithMailbox() =
        RecordingMailboxStore().also { it.create(RegistrationTestSupport.mailbox(), emptyList()) }

    private fun assertRejected(
        expected: LoginRejection,
        login: Login,
        service: LoginService = LoginTestSupport.service(store = storeWithMailbox()),
    ) {
        assertEquals(LoginResult.Rejected(expected), service.login(login))
    }

    private fun forgedChallenge() =
        ChallengeTestSupport
            .service()
            .issue()
            .bytes
            .also { it[it.lastIndex] = 0 }

    @Test
    fun `a valid login is answered with a token`() {
        val result = LoginTestSupport.service(store = storeWithMailbox()).login(LoginTestSupport.login())

        assertEquals("token-for-$MAILBOX_ID", assertIs<LoginResult.LoggedIn>(result).token.value)
    }

    @Test
    fun `an expired challenge is rejected`() {
        val service =
            LoginTestSupport.service(
                store = storeWithMailbox(),
                atMillis = ISSUED_AT_MILLIS + LIFETIME_MILLIS,
            )

        assertRejected(LoginRejection.INVALID_CHALLENGE, LoginTestSupport.login(), service)
    }

    @Test
    fun `a forged challenge is rejected`() {
        assertRejected(LoginRejection.INVALID_CHALLENGE, LoginTestSupport.login(challenge = forgedChallenge()))
    }

    @Test
    fun `a challenge of the wrong length is malformed`() {
        assertRejected(LoginRejection.MALFORMED, LoginTestSupport.login(challenge = ByteArray(10)))
    }

    @Test
    fun `malformed mailbox ids and signatures are rejected`() {
        assertRejected(LoginRejection.MALFORMED, LoginTestSupport.login(mailboxId = "mbx_short"))
        assertRejected(LoginRejection.MALFORMED, LoginTestSupport.login(signature = ByteArray(63)))
    }

    @Test
    fun `an unknown mailbox is rejected like an invalid signature`() {
        val unknown = LoginTestSupport.service()

        assertRejected(LoginRejection.INVALID_CREDENTIALS, LoginTestSupport.login(), unknown)
    }

    @Test
    fun `an invalid signature is rejected`() {
        val verifier = FakePublicKeyVerifier(isSignatureValid = { false })
        val service = LoginTestSupport.service(verifier = verifier, store = storeWithMailbox())

        assertRejected(LoginRejection.INVALID_CREDENTIALS, LoginTestSupport.login(), service)
    }

    @Test
    fun `the challenge is checked before the mailbox is looked up`() {
        assertRejected(
            LoginRejection.INVALID_CHALLENGE,
            LoginTestSupport.login(challenge = forgedChallenge()),
            LoginTestSupport.service(),
        )
    }

    @Test
    fun `the structure is checked before the signature`() {
        val verifier = FakePublicKeyVerifier(isSignatureValid = { false })
        val service = LoginTestSupport.service(verifier = verifier, store = storeWithMailbox())

        assertRejected(LoginRejection.MALFORMED, LoginTestSupport.login(signature = ByteArray(10)), service)
        assertTrue(verifier.verifications.isEmpty())
    }

    @Test
    fun `the signature is verified under the stored key over the rebuilt login message`() {
        val storedKey = ByteArray(33) { 7 }
        val store = RecordingMailboxStore().also { it.create(RegistrationTestSupport.mailbox(storedKey), emptyList()) }
        val verifier = FakePublicKeyVerifier()
        val login = LoginTestSupport.login()

        LoginTestSupport.service(verifier = verifier, store = store).login(login)

        val verification = verifier.verifications.single()
        assertContentEquals(storedKey, verification.signerKey)
        assertContentEquals(SignedMessages.login(login.challenge, login.mailboxId), verification.message)
        assertContentEquals(login.signature, verification.signature)
    }
}
