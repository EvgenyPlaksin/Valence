package com.lnight.valence.server

import com.lnight.valence.protocol.SignedMessages
import com.lnight.valence.server.RegistrationTestSupport.MAILBOX_ID
import com.lnight.valence.server.crypto.LibsignalPublicKeyVerifier
import com.lnight.valence.server.domain.Login
import com.lnight.valence.server.domain.LoginRejection
import com.lnight.valence.server.domain.LoginResult
import com.lnight.valence.server.store.InMemoryMailboxStore
import org.signal.libsignal.protocol.ecc.ECKeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LoginEndToEndTest {
    private val identity = ECKeyPair.generate()
    private val identityPublic = identity.publicKey.serialize()
    private val store =
        InMemoryMailboxStore().also { it.create(RegistrationTestSupport.mailbox(identityPublic), emptyList()) }
    private val service = LoginTestSupport.service(verifier = LibsignalPublicKeyVerifier(), store = store)

    private fun login(
        signer: ECKeyPair = identity,
        message: (ByteArray) -> ByteArray = { SignedMessages.login(it, MAILBOX_ID) },
    ): Login {
        val challenge = ChallengeTestSupport.service().issue().bytes
        return Login(challenge, MAILBOX_ID, signer.privateKey.calculateSignature(message(challenge)))
    }

    private fun invalidCredentials() = LoginResult.Rejected(LoginRejection.INVALID_CREDENTIALS)

    @Test
    fun `a login signed with the identity key is accepted`() {
        assertIs<LoginResult.LoggedIn>(service.login(login()))
    }

    @Test
    fun `a login signed by another key is rejected`() {
        assertEquals(invalidCredentials(), service.login(login(signer = ECKeyPair.generate())))
    }

    @Test
    fun `a login signed for another mailbox is rejected`() {
        val result = service.login(login(message = { SignedMessages.login(it, "mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa") }))

        assertEquals(invalidCredentials(), result)
    }

    @Test
    fun `a registration proof is not accepted as a login signature`() {
        val result =
            service.login(
                login(message = { SignedMessages.register(it, MAILBOX_ID, identityPublic, ByteArray(32) { 9 }) }),
            )

        assertEquals(invalidCredentials(), result)
    }
}
