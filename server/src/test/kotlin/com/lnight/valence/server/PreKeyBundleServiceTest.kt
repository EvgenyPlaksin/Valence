package com.lnight.valence.server

import com.lnight.valence.server.domain.BundleResult
import com.lnight.valence.server.domain.DeliveryKeyAuthenticator
import com.lnight.valence.server.domain.OneTimeKyberPreKey
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.PreKeyBundleService
import com.lnight.valence.server.domain.RateLimitPolicy
import com.lnight.valence.server.domain.TokenBucketRateLimiter
import com.lnight.valence.server.store.InMemoryMailboxStore
import kotlinx.coroutines.runBlocking
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
    private val clock = TestClock(ChallengeTestSupport.ISSUED_AT_MILLIS)

    private fun store(
        oneTimeCount: Int = 2,
        oneTimeKyberCount: Int = 2,
    ) = InMemoryMailboxStore().seed(
        DeliveryKeyTestSupport.mailbox(),
        (1..oneTimeCount).map { n -> OneTimePreKey(n, ByteArray(33) { 7 }) },
        (101 until 101 + oneTimeKyberCount).map { n ->
            OneTimeKyberPreKey(n, ByteArray(1569) { 8 }, ByteArray(64) { 9 })
        },
    )

    private fun service(
        store: InMemoryMailboxStore,
        policy: RateLimitPolicy = RateLimitPolicy(burst = 100, refillPerSecond = 1.0),
    ) = PreKeyBundleService(DeliveryKeyAuthenticator(store), store, TokenBucketRateLimiter(clock), policy)

    private suspend fun PreKeyBundleService.bundle(
        mailboxId: String = id,
        deliveryKey: ByteArray = key,
    ) = (fetch(mailboxId, deliveryKey) as? BundleResult.Found)?.bundle

    @Test
    fun `an authorised fetch returns the stored keys`() =
        runBlocking {
            val bundle = assertNotNull(service(store()).bundle())

            assertContentEquals(ByteArray(33) { 4 }, bundle.identityKey)
            assertEquals(1, bundle.signedPreKey.id)
            assertEquals(1, bundle.kyberPreKey.id)
            assertEquals(1, bundle.oneTimePreKey?.id)
            assertEquals(101, bundle.oneTimeKyberPreKey?.id)
        }

    @Test
    fun `each fetch consumes the oldest one time key`() =
        runBlocking {
            val store = store()
            val service = service(store)
            val first = service.bundle()
            val second = service.bundle()
            val exhausted = service.bundle()

            assertEquals(1, first?.oneTimePreKey?.id)
            assertEquals(101, first?.oneTimeKyberPreKey?.id)
            assertEquals(2, second?.oneTimePreKey?.id)
            assertEquals(102, second?.oneTimeKyberPreKey?.id)
            assertNull(exhausted?.oneTimePreKey)
            assertNull(exhausted?.oneTimeKyberPreKey)
            assertEquals(0, store.oneTimePreKeyCount(id))
            assertEquals(0, store.oneTimeKyberPreKeyCount(id))
        }

    @Test
    fun `an exhausted mailbox still returns the signed and kyber prekeys`() =
        runBlocking {
            val bundle = assertNotNull(service(store(oneTimeCount = 0, oneTimeKyberCount = 0)).bundle())

            assertNull(bundle.oneTimePreKey)
            assertNull(bundle.oneTimeKyberPreKey)
            assertEquals(1, bundle.signedPreKey.id)
            assertEquals(1, bundle.kyberPreKey.id)
        }

    @Test
    fun `an exhausted kyber queue falls back while ec one time keys remain`() =
        runBlocking {
            val store = store(oneTimeCount = 1, oneTimeKyberCount = 0)
            val bundle = assertNotNull(service(store).bundle())

            assertEquals(1, bundle.oneTimePreKey?.id)
            assertNull(bundle.oneTimeKyberPreKey)
            assertEquals(1, bundle.kyberPreKey.id)
            assertEquals(0, store.oneTimePreKeyCount(id))
        }

    @Test
    fun `an exhausted ec queue does not prevent kyber one time delivery`() =
        runBlocking {
            val store = store(oneTimeCount = 0, oneTimeKyberCount = 1)
            val bundle = assertNotNull(service(store).bundle())

            assertNull(bundle.oneTimePreKey)
            assertEquals(101, bundle.oneTimeKyberPreKey?.id)
            assertEquals(0, store.oneTimeKyberPreKeyCount(id))
        }

    @Test
    fun `a wrong key is unauthorised and consumes nothing`() =
        runBlocking {
            val store = store()

            assertEquals(BundleResult.Unauthorized, service(store).fetch(id, ByteArray(16) { 4 }))
            assertEquals(2, store.oneTimePreKeyCount(id))
            assertEquals(2, store.oneTimeKyberPreKeyCount(id))
        }

    @Test
    fun `an unknown mailbox is unauthorised`() =
        runBlocking {
            assertEquals(BundleResult.Unauthorized, service(store()).fetch("mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa", key))
        }

    @Test
    fun `fetches beyond the recipient limit are limited and consume nothing`() =
        runBlocking {
            val store = store(oneTimeCount = 5)
            val service = service(store, RateLimitPolicy(burst = 2, refillPerSecond = 0.1))

            assertNotNull(service.bundle())
            assertNotNull(service.bundle())

            assertEquals(BundleResult.RateLimited(10_000L), service.fetch(id, key))
            assertEquals(3, store.oneTimePreKeyCount(id))
        }

    @Test
    fun `unauthorised attempts do not use up the recipient limit`() =
        runBlocking {
            val service = service(store(), RateLimitPolicy(burst = 1, refillPerSecond = 0.1))

            repeat(10) { service.fetch(id, ByteArray(16) { 4 }) }

            assertNotNull(service.bundle())
        }

    @Test
    fun `concurrent fetches hand out every one time key exactly once`() {
        val service = service(store(oneTimeCount = 20))
        val executor = Executors.newFixedThreadPool(8)

        val bundles = (1..32).map { executor.submit(Callable { runBlocking { service.bundle() } }) }.map { it.get() }
        executor.shutdown()

        val handedOut = bundles.mapNotNull { it?.oneTimePreKey?.id }
        val kyberHandedOut = bundles.mapNotNull { it?.oneTimeKyberPreKey?.id }
        assertEquals(32, bundles.count { it != null })
        assertEquals(20, handedOut.size)
        assertEquals(20, handedOut.toSet().size)
        assertEquals(2, kyberHandedOut.size)
        assertEquals(2, kyberHandedOut.toSet().size)
    }
}
