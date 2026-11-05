package com.lnight.valence.server

import com.lnight.valence.server.domain.DeliveryKeyAuthenticator
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.PreKeyBundleService
import com.lnight.valence.server.store.InMemoryMailboxStore
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PreKeyBundleServiceTest {
    private val id = RegistrationTestSupport.MAILBOX_ID
    private val key = DeliveryKeyTestSupport.deliveryKey

    private fun store(oneTimeCount: Int = 2) =
        InMemoryMailboxStore().also {
            val keys = (1..oneTimeCount).map { n -> OneTimePreKey(n, ByteArray(33) { 7 }) }
            it.create(DeliveryKeyTestSupport.mailbox(), keys)
        }

    private fun service(store: InMemoryMailboxStore) = PreKeyBundleService(DeliveryKeyAuthenticator(store), store)

    @Test
    fun `an authorised fetch returns the stored keys`() {
        val bundle = assertNotNull(service(store()).fetch(id, key))

        assertContentEquals(ByteArray(33) { 4 }, bundle.identityKey)
        assertEquals(1, bundle.signedPreKey.id)
        assertEquals(1, bundle.kyberPreKey.id)
        assertEquals(1, bundle.oneTimePreKey?.id)
    }

    @Test
    fun `each fetch consumes the oldest one time key`() {
        val store = store()
        val service = service(store)

        assertEquals(1, service.fetch(id, key)?.oneTimePreKey?.id)
        assertEquals(2, service.fetch(id, key)?.oneTimePreKey?.id)
        assertEquals(0, store.oneTimePreKeyCount(id))
    }

    @Test
    fun `an exhausted mailbox still returns the signed and kyber prekeys`() {
        val service = service(store(oneTimeCount = 0))

        val bundle = assertNotNull(service.fetch(id, key))

        assertNull(bundle.oneTimePreKey)
        assertEquals(1, bundle.signedPreKey.id)
        assertEquals(1, bundle.kyberPreKey.id)
    }

    @Test
    fun `a wrong key returns nothing and consumes nothing`() {
        val store = store()

        assertNull(service(store).fetch(id, ByteArray(16) { 4 }))
        assertEquals(2, store.oneTimePreKeyCount(id))
    }

    @Test
    fun `an unknown mailbox returns nothing`() {
        assertNull(service(store()).fetch("mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa", key))
    }

    @Test
    fun `concurrent fetches hand out every one time key exactly once`() {
        val service = service(store(oneTimeCount = 20))
        val executor = Executors.newFixedThreadPool(8)

        val bundles = (1..32).map { executor.submit(Callable { service.fetch(id, key) }) }.map { it.get() }
        executor.shutdown()

        val handedOut = bundles.mapNotNull { it?.oneTimePreKey?.id }
        assertEquals(32, bundles.count { it != null })
        assertEquals(20, handedOut.size)
        assertEquals(20, handedOut.toSet().size)
    }
}
