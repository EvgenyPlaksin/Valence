package com.lnight.valence.server.store

import com.lnight.valence.server.RegistrationTestSupport
import com.lnight.valence.server.domain.Mailbox
import com.lnight.valence.server.domain.SignedPublicKey
import java.util.concurrent.Callable
import java.util.concurrent.Executors
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
    fun `a new mailbox is created and found`() {
        val store = InMemoryMailboxStore()
        val mailbox = mailbox()

        assertTrue(store.create(mailbox, RegistrationTestSupport.defaultOneTimePreKeys()))
        assertSame(mailbox, store.find(mailbox.id))
    }

    @Test
    fun `an existing mailbox is not replaced`() {
        val store = InMemoryMailboxStore()
        val first = mailbox()

        store.create(first, emptyList())

        assertFalse(store.create(mailbox(), emptyList()))
        assertSame(first, store.find(first.id))
    }

    @Test
    fun `an unknown mailbox is not found`() {
        assertNull(InMemoryMailboxStore().find(RegistrationTestSupport.MAILBOX_ID))
    }

    @Test
    fun `only one of many concurrent creations wins`() {
        val store = InMemoryMailboxStore()
        val executor = Executors.newFixedThreadPool(8)

        val results =
            (1..32)
                .map {
                    executor.submit(
                        Callable { store.create(mailbox(), emptyList()) },
                    )
                }.map { it.get() }
        executor.shutdown()

        assertEquals(1, results.count { it })
    }
}
