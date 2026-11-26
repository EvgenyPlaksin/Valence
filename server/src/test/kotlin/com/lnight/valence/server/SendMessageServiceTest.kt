package com.lnight.valence.server

import com.lnight.valence.server.domain.DeliveryKeyAuthenticator
import com.lnight.valence.server.domain.MessageNotifier
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.domain.RateLimitPolicy
import com.lnight.valence.server.domain.SendMessageService
import com.lnight.valence.server.domain.SendRejection
import com.lnight.valence.server.domain.SendResult
import com.lnight.valence.server.domain.TokenBucketRateLimiter
import com.lnight.valence.server.store.InMemoryInboxStore
import com.lnight.valence.server.store.InMemoryMailboxStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendMessageServiceTest {
    private val now = ChallengeTestSupport.ISSUED_AT_MILLIS
    private val id = RegistrationTestSupport.MAILBOX_ID
    private val thirtyDaysMillis = 2_592_000_000L
    private val mailboxes = InMemoryMailboxStore()
    private val inbox = InMemoryInboxStore()
    private val clock = TestClock(now)
    private val notified = mutableListOf<String>()
    private val service = serviceWith(RateLimitPolicy(burst = 100, refillPerSecond = 1.0))

    private fun serviceWith(policy: RateLimitPolicy) =
        SendMessageService(
            DeliveryKeyAuthenticator(mailboxes),
            inbox,
            MessageNotifier { notified += it },
            TokenBucketRateLimiter(clock),
            policy,
            clock,
        )

    private suspend fun send(
        envelope: ByteArray = ByteArray(10) { 1 },
        ttl: Long? = null,
        deliveryKey: ByteArray = DeliveryKeyTestSupport.deliveryKey,
        mailboxId: String = id,
    ) = service.send(mailboxId, deliveryKey, envelope, ttl)

    private fun rejected(reason: SendRejection) = SendResult.Rejected(reason)

    private suspend fun stored() = inbox.messagesAfter(id, 0)

    @Test
    fun `an accepted message is stored with the server time and the default ttl`() =
        runTest {
            mailboxes.create(DeliveryKeyTestSupport.mailbox(), emptyList())
            val envelope = ByteArray(10) { 7 }

            assertEquals(SendResult.Accepted(now), send(envelope = envelope))

            val message = stored().single()
            assertEquals(1L, message.serverSeq)
            assertEquals(now, message.serverTsMillis)
            assertEquals(now + thirtyDaysMillis, message.expiresAtMillis)
            assertContentEquals(envelope, message.envelope)
        }

    @Test
    fun `a ttl is honoured and a longer one is clamped to 30 days`() =
        runTest {
            mailboxes.create(DeliveryKeyTestSupport.mailbox(), emptyList())
            send(ttl = 60L)
            send(ttl = Long.MAX_VALUE)

            assertEquals(listOf(now + 60_000L, now + thirtyDaysMillis), stored().map { it.expiresAtMillis })
        }

    @Test
    fun `a ttl below one and an empty envelope are rejected`() =
        runTest {
            mailboxes.create(DeliveryKeyTestSupport.mailbox(), emptyList())
            assertEquals(rejected(SendRejection.INVALID_REQUEST), send(ttl = 0L))
            assertEquals(rejected(SendRejection.INVALID_REQUEST), send(ttl = -5L))
            assertEquals(rejected(SendRejection.INVALID_REQUEST), send(envelope = ByteArray(0)))
            assertTrue(stored().isEmpty())
        }

    @Test
    fun `an envelope over 64 KiB is rejected and exactly 64 KiB is accepted`() =
        runTest {
            mailboxes.create(DeliveryKeyTestSupport.mailbox(), emptyList())
            assertEquals(rejected(SendRejection.TOO_LARGE), send(envelope = ByteArray(65_537)))
            assertEquals(SendResult.Accepted(now), send(envelope = ByteArray(65_536)))
        }

    @Test
    fun `a wrong key and an unknown mailbox are unauthorised and store nothing`() =
        runTest {
            mailboxes.create(DeliveryKeyTestSupport.mailbox(), emptyList())
            assertEquals(rejected(SendRejection.UNAUTHORIZED), send(deliveryKey = ByteArray(16) { 4 }))
            assertEquals(rejected(SendRejection.UNAUTHORIZED), send(mailboxId = "mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa"))
            assertTrue(stored().isEmpty())
        }

    @Test
    fun `authorisation is checked before the request is validated`() =
        runTest {
            mailboxes.create(DeliveryKeyTestSupport.mailbox(), emptyList())
            val result = send(envelope = ByteArray(0), deliveryKey = ByteArray(16) { 4 })

            assertEquals(rejected(SendRejection.UNAUTHORIZED), result)
        }

    @Test
    fun `a full mailbox is rejected`() =
        runTest {
            mailboxes.create(DeliveryKeyTestSupport.mailbox(), emptyList())
            repeat(1_000) {
                inbox.append(
                    id,
                    DeliveryKeyTestSupport.mailbox().deliveryKeyHash,
                    NewMessage(ByteArray(1), now, now + 86_400_000L),
                    1_000,
                )
            }

            assertEquals(rejected(SendRejection.QUOTA_EXCEEDED), send())
        }

    @Test
    fun `messages receive consecutive sequence numbers`() =
        runTest {
            mailboxes.create(DeliveryKeyTestSupport.mailbox(), emptyList())
            send()
            send()

            assertEquals(listOf(1L, 2L), stored().map { it.serverSeq })
        }

    @Test
    fun `an accepted message notifies its mailbox once`() =
        runTest {
            mailboxes.create(DeliveryKeyTestSupport.mailbox(), emptyList())
            send()

            assertEquals(listOf(id), notified)
        }

    @Test
    fun `rejected messages notify nobody`() =
        runTest {
            mailboxes.create(DeliveryKeyTestSupport.mailbox(), emptyList())
            send(ttl = 0L)
            send(deliveryKey = ByteArray(16) { 4 })
            send(envelope = ByteArray(65_537))

            assertTrue(notified.isEmpty())
        }

    @Test
    fun `a mailbox that receives too many messages is limited with a retry hint`() =
        runTest {
            mailboxes.create(DeliveryKeyTestSupport.mailbox(), emptyList())
            val limited = serviceWith(RateLimitPolicy(burst = 2, refillPerSecond = 1.0))
            val envelope = ByteArray(10) { 1 }
            val key = DeliveryKeyTestSupport.deliveryKey

            repeat(2) { assertEquals(SendResult.Accepted(now), limited.send(id, key, envelope, null)) }
            assertEquals(SendResult.RateLimited(1_000L), limited.send(id, key, envelope, null))
            clock.advance(1_000L)
            assertEquals(SendResult.Accepted(now + 1_000L), limited.send(id, key, envelope, null))
        }

    @Test
    fun `unauthorised attempts do not use up the mailbox limit`() =
        runTest {
            mailboxes.create(DeliveryKeyTestSupport.mailbox(), emptyList())
            val limited = serviceWith(RateLimitPolicy(burst = 2, refillPerSecond = 1.0))
            val envelope = ByteArray(10) { 1 }

            repeat(10) { limited.send(id, ByteArray(16) { 4 }, envelope, null) }

            val result = limited.send(id, DeliveryKeyTestSupport.deliveryKey, envelope, null)
            assertEquals(SendResult.Accepted(now), result)
        }
}
