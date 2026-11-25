package com.lnight.valence.server.store

import com.lnight.valence.server.RegistrationTestSupport
import com.lnight.valence.server.domain.KeyStoreOutcome
import com.lnight.valence.server.domain.OneTimeKyberPreKey
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.SignedPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryMailboxStoreKeysTest {
    private val id = RegistrationTestSupport.MAILBOX_ID

    private fun keys(ids: IntRange) = ids.map { OneTimePreKey(it, ByteArray(33) { 7 }) }

    private fun kyberKeys(ids: IntRange) =
        ids.map { OneTimeKyberPreKey(it, ByteArray(1569) { 8 }, ByteArray(64) { 9 }) }

    private suspend fun store(oneTimeCount: Int = 2) =
        InMemoryMailboxStore().also {
            it.create(
                RegistrationTestSupport.mailbox(),
                keys(1..oneTimeCount),
                RegistrationTestSupport.defaultOneTimeKyberPreKeys(),
            )
        }

    private fun signed(id: Int) = SignedPublicKey(id, ByteArray(33) { 5 }, ByteArray(64) { 2 })

    private fun kyber(id: Int) = SignedPublicKey(id, ByteArray(1569) { 6 }, ByteArray(64) { 3 })

    @Test
    fun `the count reflects the stored one time keys`() =
        runTest {
            assertEquals(2, store().oneTimePreKeyCount(id))
            assertEquals(2, store().oneTimeKyberPreKeyCount(id))
            assertNull(store().oneTimePreKeyCount("mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa"))
        }

    @Test
    fun `replacing signed prekeys stores both new keys`() =
        runTest {
            val store = store()

            assertEquals(KeyStoreOutcome.UPDATED, store.replaceSignedPreKeys(id, signed(2), kyber(2)))
            assertEquals(2, store.find(id)?.signedPreKey?.id)
            assertEquals(2, store.find(id)?.kyberPreKey?.id)
        }

    @Test
    fun `reusing the stored id for either kind is a conflict`() =
        runTest {
            val store = store()

            assertEquals(
                KeyStoreOutcome.ID_EXISTS,
                store.replaceSignedPreKeys(id, signed(1), kyber(2)),
            )
            assertEquals(
                KeyStoreOutcome.ID_EXISTS,
                store.replaceSignedPreKeys(id, signed(2), kyber(1)),
            )
            assertEquals(1, store.find(id)?.signedPreKey?.id)
        }

    @Test
    fun `replacing prekeys of an unknown mailbox reports it`() =
        runTest {
            val outcome =
                store().replaceSignedPreKeys("mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa", signed(2), kyber(2))

            assertEquals(KeyStoreOutcome.UNKNOWN_MAILBOX, outcome)
        }

    @Test
    fun `adding one time keys increases the count`() =
        runTest {
            val store = store()

            assertEquals(KeyStoreOutcome.UPDATED, store.addOneTimePreKeys(id, keys(101..102), 200))
            assertEquals(4, store.oneTimePreKeyCount(id))
        }

    @Test
    fun `adding one time kyber keys increases its independent count`() =
        runTest {
            val store = store()

            assertEquals(KeyStoreOutcome.UPDATED, store.addOneTimeKyberPreKeys(id, kyberKeys(103..104), 200))
            assertEquals(4, store.oneTimeKyberPreKeyCount(id))
            assertEquals(2, store.oneTimePreKeyCount(id))
        }

    @Test
    fun `one time kyber additions and last resort replacements reject colliding ids`() =
        runTest {
            val store = store()

            assertEquals(KeyStoreOutcome.ID_EXISTS, store.addOneTimeKyberPreKeys(id, kyberKeys(1..1), 200))
            assertEquals(KeyStoreOutcome.ID_EXISTS, store.addOneTimeKyberPreKeys(id, kyberKeys(101..101), 200))
            assertEquals(KeyStoreOutcome.ID_EXISTS, store.replaceSignedPreKeys(id, signed(2), kyber(101)))
        }

    @Test
    fun `an existing id or an exceeded limit changes nothing`() =
        runTest {
            val store = store()

            assertEquals(KeyStoreOutcome.ID_EXISTS, store.addOneTimePreKeys(id, keys(2..3), 200))
            assertEquals(
                KeyStoreOutcome.LIMIT_EXCEEDED,
                store.addOneTimePreKeys(id, keys(101..300), 200),
            )
            assertEquals(2, store.oneTimePreKeyCount(id))
        }

    @Test
    fun `adding keys to an unknown mailbox reports it`() =
        runTest {
            val outcome =
                store().addOneTimePreKeys("mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa", keys(101..102), 200)

            assertEquals(KeyStoreOutcome.UNKNOWN_MAILBOX, outcome)
        }

    @Test
    fun `concurrent additions never exceed the limit`() =
        runTest {
            val store = store(oneTimeCount = 0)

            val outcomes =
                (0 until 32)
                    .map { batch ->
                        async(Dispatchers.Default) {
                            store.addOneTimePreKeys(id, keys(batch * 10 + 1..batch * 10 + 10), 200)
                        }
                    }.awaitAll()

            assertEquals(20, outcomes.count { it == KeyStoreOutcome.UPDATED })
            assertEquals(200, store.oneTimePreKeyCount(id))
        }
}
