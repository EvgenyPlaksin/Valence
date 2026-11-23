package com.lnight.valence.server.store

import com.lnight.valence.server.RegistrationTestSupport
import com.lnight.valence.server.domain.Mailbox
import com.lnight.valence.server.domain.SignedPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InMemoryMailboxStoreTest {
    private fun mailbox(id: String = RegistrationTestSupport.MAILBOX_ID) =
        Mailbox(
            id = id,
            identityKey = ByteArray(33),
            signedPreKey = SignedPublicKey(1, ByteArray(33), ByteArray(64)),
            kyberPreKey = SignedPublicKey(1, ByteArray(1569), ByteArray(64)),
            deliveryKeyHash = ByteArray(32),
            createdAtMillis = 0L,
        )

    @Test
    fun `a new mailbox is created and found`() =
        runTest {
            val store = InMemoryMailboxStore()
            val mailbox = mailbox()

            assertTrue(store.create(mailbox, RegistrationTestSupport.defaultOneTimePreKeys()))
            assertSame(mailbox, store.find(mailbox.id))
        }

    @Test
    fun `an existing mailbox is not replaced`() =
        runTest {
            val store = InMemoryMailboxStore()
            val first = mailbox()

            store.create(first, emptyList())

            assertFalse(store.create(mailbox(), emptyList()))
            assertSame(first, store.find(first.id))
        }

    @Test
    fun `an unknown mailbox is not found`() =
        runTest {
            assertNull(InMemoryMailboxStore().find(RegistrationTestSupport.MAILBOX_ID))
        }

    @Test
    fun `only one of many concurrent creations wins`() =
        runTest {
            val store = InMemoryMailboxStore()

            val results =
                (1..32).map { async(Dispatchers.Default) { store.create(mailbox(), emptyList()) } }.awaitAll()

            assertEquals(1, results.count { it })
        }
}
