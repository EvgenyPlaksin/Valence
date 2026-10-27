package com.lnight.valence.server

import com.lnight.valence.protocol.SignedMessages
import com.lnight.valence.server.ChallengeTestSupport.ISSUED_AT_MILLIS
import com.lnight.valence.server.ChallengeTestSupport.LIFETIME_MILLIS
import com.lnight.valence.server.RegistrationTestSupport.MAILBOX_ID
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.Registration
import com.lnight.valence.server.domain.RegistrationRejection
import com.lnight.valence.server.domain.RegistrationResult
import com.lnight.valence.server.domain.RegistrationService
import com.lnight.valence.server.domain.SignedPublicKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RegistrationServiceTest {
    private fun assertRejected(
        expected: RegistrationRejection,
        registration: Registration,
        service: RegistrationService = RegistrationTestSupport.service(),
    ) {
        assertEquals(RegistrationResult.Rejected(expected), service.register(registration))
    }

    private fun rejectingProof() =
        FakePublicKeyVerifier(isSignatureValid = { !it.contentEquals(RegistrationTestSupport.validProof) })

    @Test
    fun `a valid registration is stored and answered with a token`() {
        val store = RecordingMailboxStore()

        val result = RegistrationTestSupport.service(store = store).register(RegistrationTestSupport.registration())

        val registered = assertIs<RegistrationResult.Registered>(result)
        assertEquals(MAILBOX_ID, registered.mailboxId)
        assertEquals("token-for-$MAILBOX_ID", registered.token.value)
        assertEquals(listOf(MAILBOX_ID), store.created.map { it.id })
        assertEquals(2, store.createdOneTimeKeys.single().size)
    }

    @Test
    fun `an expired challenge is rejected`() {
        val service = RegistrationTestSupport.service(atMillis = ISSUED_AT_MILLIS + LIFETIME_MILLIS)

        assertRejected(RegistrationRejection.INVALID_CHALLENGE, RegistrationTestSupport.registration(), service)
    }

    @Test
    fun `a forged challenge is rejected`() {
        val forged =
            ChallengeTestSupport
                .service()
                .issue()
                .bytes
                .also { it[it.lastIndex] = 0 }

        assertRejected(
            RegistrationRejection.INVALID_CHALLENGE,
            RegistrationTestSupport.registration(challenge = forged),
        )
    }

    @Test
    fun `a challenge of the wrong length is malformed`() {
        assertRejected(RegistrationRejection.MALFORMED, RegistrationTestSupport.registration(challenge = ByteArray(10)))
    }

    @Test
    fun `malformed mailbox ids are rejected`() {
        listOf(
            "mbx_short",
            "MBX_G4R2K7X5QMWV6BNZDCS3N4PE5A",
            "xyz_g4r2k7x5qmwv6bnzdcs3n4pe5a",
            "mbx_g4r2k7x5qmwv6bnzdcs3n4pe58",
        ).forEach {
            assertRejected(
                RegistrationRejection.MALFORMED,
                RegistrationTestSupport.registration(mailboxId = it),
            )
        }
    }

    @Test
    fun `wrong sizes are rejected`() {
        val signed = SignedPublicKey(1, ByteArray(33) { 5 }, ByteArray(63))

        assertRejected(RegistrationRejection.MALFORMED, RegistrationTestSupport.registration(proof = ByteArray(63)))
        assertRejected(
            RegistrationRejection.MALFORMED,
            RegistrationTestSupport.registration(deliveryKeyHash = ByteArray(31)),
        )
        assertRejected(RegistrationRejection.MALFORMED, RegistrationTestSupport.registration(signedPreKey = signed))
    }

    @Test
    fun `invalid keys are rejected`() {
        val service = RegistrationTestSupport.service(verifier = FakePublicKeyVerifier(isKeyValid = { false }))

        assertRejected(RegistrationRejection.MALFORMED, RegistrationTestSupport.registration(), service)
    }

    @Test
    fun `one time prekey sets outside the allowed bounds are rejected`() {
        val tooMany = (1..101).map { OneTimePreKey(it, ByteArray(33) { 7 }) }
        val duplicated = listOf(OneTimePreKey(1, ByteArray(33) { 7 }), OneTimePreKey(1, ByteArray(33) { 8 }))
        val zeroId = listOf(OneTimePreKey(0, ByteArray(33) { 7 }))

        listOf(emptyList(), tooMany, duplicated, zeroId).forEach {
            assertRejected(RegistrationRejection.MALFORMED, RegistrationTestSupport.registration(oneTimePreKeys = it))
        }
    }

    @Test
    fun `an invalid proof is rejected and nothing is stored`() {
        val store = RecordingMailboxStore()
        val service = RegistrationTestSupport.service(verifier = rejectingProof(), store = store)

        assertRejected(RegistrationRejection.INVALID_PROOF, RegistrationTestSupport.registration(), service)
        assertTrue(store.created.isEmpty())
    }

    @Test
    fun `an invalid prekey signature is rejected`() {
        val signedRejected =
            FakePublicKeyVerifier(
                isSignatureValid = { !it.contentEquals(RegistrationTestSupport.signedPreKeySignature) },
            )
        val kyberRejected =
            FakePublicKeyVerifier(
                isSignatureValid = { !it.contentEquals(RegistrationTestSupport.kyberPreKeySignature) },
            )

        assertRejected(
            RegistrationRejection.INVALID_PREKEY_SIGNATURE,
            RegistrationTestSupport.registration(),
            RegistrationTestSupport.service(verifier = signedRejected),
        )
        assertRejected(
            RegistrationRejection.INVALID_PREKEY_SIGNATURE,
            RegistrationTestSupport.registration(),
            RegistrationTestSupport.service(verifier = kyberRejected),
        )
    }

    @Test
    fun `an existing mailbox is rejected`() {
        val service = RegistrationTestSupport.service(store = RecordingMailboxStore(accepts = false))

        assertRejected(RegistrationRejection.MAILBOX_EXISTS, RegistrationTestSupport.registration(), service)
    }

    @Test
    fun `the challenge is checked before the keys`() {
        val service = RegistrationTestSupport.service(verifier = FakePublicKeyVerifier(isKeyValid = { false }))
        val forged =
            ChallengeTestSupport
                .service()
                .issue()
                .bytes
                .also { it[it.lastIndex] = 0 }

        assertRejected(
            RegistrationRejection.INVALID_CHALLENGE,
            RegistrationTestSupport.registration(challenge = forged),
            service,
        )
    }

    @Test
    fun `the structure is checked before the signatures`() {
        val verifier = FakePublicKeyVerifier(isKeyValid = { false }, isSignatureValid = { false })

        assertRejected(
            RegistrationRejection.MALFORMED,
            RegistrationTestSupport.registration(),
            RegistrationTestSupport.service(verifier = verifier),
        )
        assertTrue(verifier.verifications.isEmpty())
    }

    @Test
    fun `the proof is verified over the message rebuilt from the received fields`() {
        val verifier = FakePublicKeyVerifier()
        val registration = RegistrationTestSupport.registration()

        RegistrationTestSupport.service(verifier = verifier).register(registration)

        val expected =
            SignedMessages.register(
                challenge = registration.challenge,
                mailboxId = registration.mailboxId,
                identityKey = registration.identityKey,
                deliveryKeyHash = registration.deliveryKeyHash,
            )
        val proofCheck = verifier.verifications.first { it.signature.contentEquals(registration.proof) }
        assertContentEquals(expected, proofCheck.message)
    }
}
