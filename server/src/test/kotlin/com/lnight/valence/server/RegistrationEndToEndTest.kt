package com.lnight.valence.server

import com.lnight.valence.protocol.SignedMessages
import com.lnight.valence.server.RegistrationTestSupport.MAILBOX_ID
import com.lnight.valence.server.crypto.LibsignalPublicKeyVerifier
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.Registration
import com.lnight.valence.server.domain.RegistrationRejection
import com.lnight.valence.server.domain.RegistrationResult
import com.lnight.valence.server.domain.SignedPublicKey
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RegistrationEndToEndTest {
    private val identity = ECKeyPair.generate()
    private val service = RegistrationTestSupport.service(verifier = LibsignalPublicKeyVerifier())

    private fun registration(
        sentMailboxId: String = MAILBOX_ID,
        signedMailboxId: String = sentMailboxId,
        preKeySigner: ECKeyPair = identity,
        proofIsLoginMessage: Boolean = false,
    ): Registration {
        val challenge = ChallengeTestSupport.service().issue().bytes
        val identityPublic = identity.publicKey.serialize()
        val deliveryKeyHash = ByteArray(32) { 9 }
        val signedPreKey = ECKeyPair.generate().publicKey.serialize()
        val kyberPreKey = KEMKeyPair.generate(KEMKeyType.KYBER_1024).publicKey.serialize()
        val proofMessage =
            if (proofIsLoginMessage) {
                SignedMessages.login(challenge, signedMailboxId)
            } else {
                SignedMessages.register(challenge, signedMailboxId, identityPublic, deliveryKeyHash)
            }
        return Registration(
            challenge = challenge,
            mailboxId = sentMailboxId,
            identityKey = identityPublic,
            signedPreKey = SignedPublicKey(1, signedPreKey, preKeySigner.privateKey.calculateSignature(signedPreKey)),
            kyberPreKey = SignedPublicKey(1, kyberPreKey, preKeySigner.privateKey.calculateSignature(kyberPreKey)),
            oneTimePreKeys = (1..100).map { OneTimePreKey(it, ECKeyPair.generate().publicKey.serialize()) },
            deliveryKeyHash = deliveryKeyHash,
            proof = identity.privateKey.calculateSignature(proofMessage),
        )
    }

    @Test
    fun `a registration signed with real keys is accepted`() {
        assertIs<RegistrationResult.Registered>(service.register(registration()))
    }

    @Test
    fun `a proof signed for another mailbox is rejected`() {
        val result = service.register(registration(signedMailboxId = "mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa"))

        assertEquals(RegistrationResult.Rejected(RegistrationRejection.INVALID_PROOF), result)
    }

    @Test
    fun `a login signature is not accepted as a registration proof`() {
        val result = service.register(registration(proofIsLoginMessage = true))

        assertEquals(RegistrationResult.Rejected(RegistrationRejection.INVALID_PROOF), result)
    }

    @Test
    fun `prekeys signed by another identity are rejected`() {
        val result = service.register(registration(preKeySigner = ECKeyPair.generate()))

        assertEquals(RegistrationResult.Rejected(RegistrationRejection.INVALID_PREKEY_SIGNATURE), result)
    }
}
