package com.lnight.valence.server

import com.lnight.valence.server.RegistrationTestSupport.MAILBOX_ID
import com.lnight.valence.server.domain.KeyManagementService
import com.lnight.valence.server.domain.KeyUpdateRejection
import com.lnight.valence.server.domain.KeyUpdateResult
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.PreKeyReplacement
import com.lnight.valence.server.domain.PublicKeyVerifier
import com.lnight.valence.server.domain.SignedPublicKey
import com.lnight.valence.server.store.InMemoryMailboxStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyManagementServiceTest {
    private val unknownMailbox = "mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val store =
        InMemoryMailboxStore().also {
            it.create(RegistrationTestSupport.mailbox(), RegistrationTestSupport.defaultOneTimePreKeys())
        }

    private fun service(verifier: PublicKeyVerifier = FakePublicKeyVerifier()) = KeyManagementService(verifier, store)

    private fun signed(
        id: Int,
        signatureSize: Int = 64,
    ) = SignedPublicKey(id, ByteArray(33) { 5 }, ByteArray(signatureSize) { 2 })

    private fun kyber(id: Int) = SignedPublicKey(id, ByteArray(1569) { 6 }, ByteArray(64) { 3 })

    private fun replacement(
        signedId: Int = 2,
        kyberId: Int = 2,
    ) = PreKeyReplacement(signed(signedId), kyber(kyberId))

    private fun oneTime(ids: IntRange) = ids.map { OneTimePreKey(it, ByteArray(33) { 7 }) }

    private fun rejected(reason: KeyUpdateRejection) = KeyUpdateResult.Rejected(reason)

    @Test
    fun `status reports the stored one time keys`() {
        assertEquals(2, service().status(MAILBOX_ID))
        assertNull(service().status(unknownMailbox))
    }

    @Test
    fun `new signed and kyber prekeys replace the stored ones`() {
        val result = service().replaceSignedPreKeys(MAILBOX_ID, replacement())

        assertEquals(KeyUpdateResult.Updated(2), result)
        assertEquals(2, store.find(MAILBOX_ID)?.signedPreKey?.id)
        assertEquals(2, store.find(MAILBOX_ID)?.kyberPreKey?.id)
    }

    @Test
    fun `a replacement for an unknown mailbox is rejected`() {
        assertEquals(
            rejected(KeyUpdateRejection.UNKNOWN_MAILBOX),
            service().replaceSignedPreKeys(unknownMailbox, replacement()),
        )
    }

    @Test
    fun `malformed replacements are rejected`() {
        val invalidKeys = service(FakePublicKeyVerifier(isKeyValid = { false }))

        assertEquals(
            rejected(KeyUpdateRejection.MALFORMED),
            invalidKeys.replaceSignedPreKeys(MAILBOX_ID, replacement()),
        )
        assertEquals(
            rejected(KeyUpdateRejection.MALFORMED),
            service().replaceSignedPreKeys(MAILBOX_ID, replacement(signedId = 0)),
        )
        assertEquals(
            rejected(KeyUpdateRejection.MALFORMED),
            service().replaceSignedPreKeys(MAILBOX_ID, PreKeyReplacement(signed(2, signatureSize = 63), kyber(2))),
        )
    }

    @Test
    fun `an invalid signature on either key is rejected`() {
        val signedRejected = FakePublicKeyVerifier(isSignatureValid = { !it.contentEquals(ByteArray(64) { 2 }) })
        val kyberRejected = FakePublicKeyVerifier(isSignatureValid = { !it.contentEquals(ByteArray(64) { 3 }) })

        assertEquals(
            rejected(KeyUpdateRejection.INVALID_SIGNATURE),
            service(signedRejected).replaceSignedPreKeys(MAILBOX_ID, replacement()),
        )
        assertEquals(
            rejected(KeyUpdateRejection.INVALID_SIGNATURE),
            service(kyberRejected).replaceSignedPreKeys(MAILBOX_ID, replacement()),
        )
    }

    @Test
    fun `reusing the stored id is rejected and changes nothing`() {
        assertEquals(
            rejected(KeyUpdateRejection.ID_EXISTS),
            service().replaceSignedPreKeys(MAILBOX_ID, replacement(signedId = 1)),
        )
        assertEquals(
            rejected(KeyUpdateRejection.ID_EXISTS),
            service().replaceSignedPreKeys(MAILBOX_ID, replacement(kyberId = 1)),
        )
        assertEquals(1, store.find(MAILBOX_ID)?.signedPreKey?.id)
    }

    @Test
    fun `the structure is checked before the signatures`() {
        val verifier = FakePublicKeyVerifier(isKeyValid = { false }, isSignatureValid = { false })

        service(verifier).replaceSignedPreKeys(MAILBOX_ID, replacement())

        assertTrue(verifier.verifications.isEmpty())
    }

    @Test
    fun `the prekey signatures are verified under the stored identity key`() {
        val verifier = FakePublicKeyVerifier()
        val replacement = replacement()

        service(verifier).replaceSignedPreKeys(MAILBOX_ID, replacement)

        assertEquals(2, verifier.verifications.size)
        verifier.verifications.forEach { assertContentEquals(ByteArray(33) { 4 }, it.signerKey) }
        assertContentEquals(replacement.signedPreKey.publicKey, verifier.verifications[0].message)
        assertContentEquals(replacement.kyberPreKey.publicKey, verifier.verifications[1].message)
    }

    @Test
    fun `uploaded one time keys are added`() {
        assertEquals(KeyUpdateResult.Updated(4), service().uploadOneTimePreKeys(MAILBOX_ID, oneTime(101..102)))
    }

    @Test
    fun `malformed one time key batches are rejected`() {
        val tooMany = oneTime(101..201)
        val repeated = oneTime(101..101) + oneTime(101..101)
        val zeroId = oneTime(0..0)

        listOf(emptyList(), tooMany, repeated, zeroId).forEach {
            assertEquals(rejected(KeyUpdateRejection.MALFORMED), service().uploadOneTimePreKeys(MAILBOX_ID, it))
        }
        assertEquals(
            rejected(KeyUpdateRejection.MALFORMED),
            service(FakePublicKeyVerifier(isKeyValid = { false })).uploadOneTimePreKeys(MAILBOX_ID, oneTime(101..102)),
        )
    }

    @Test
    fun `a one time key id that is already stored is rejected`() {
        assertEquals(rejected(KeyUpdateRejection.ID_EXISTS), service().uploadOneTimePreKeys(MAILBOX_ID, oneTime(2..3)))
        assertEquals(2, service().status(MAILBOX_ID))
    }

    @Test
    fun `an upload beyond the stored limit is rejected`() {
        assertEquals(KeyUpdateResult.Updated(102), service().uploadOneTimePreKeys(MAILBOX_ID, oneTime(101..200)))
        assertEquals(
            rejected(KeyUpdateRejection.MALFORMED),
            service().uploadOneTimePreKeys(MAILBOX_ID, oneTime(201..300)),
        )
        assertEquals(102, service().status(MAILBOX_ID))
    }

    @Test
    fun `an upload for an unknown mailbox is rejected`() {
        assertEquals(
            rejected(KeyUpdateRejection.UNKNOWN_MAILBOX),
            service().uploadOneTimePreKeys(unknownMailbox, oneTime(101..102)),
        )
    }
}
