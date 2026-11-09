package com.lnight.valence.server

import com.lnight.valence.server.domain.DeliveryKeyAuthenticator
import com.lnight.valence.server.domain.MessageNotifier
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.domain.SendMessageService
import com.lnight.valence.server.domain.SendRejection
import com.lnight.valence.server.domain.SendResult
import com.lnight.valence.server.store.InMemoryInboxStore
import com.lnight.valence.server.store.InMemoryMailboxStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendMessageServiceTest {
    private val now = ChallengeTestSupport.ISSUED_AT_MILLIS
    private val id = RegistrationTestSupport.MAILBOX_ID
    private val thirtyDaysMillis = 2_592_000_000L
    private val mailboxes = InMemoryMailboxStore().also { it.create(DeliveryKeyTestSupport.mailbox(), emptyList()) }
    private val inbox = InMemoryInboxStore()
    private val notified = mutableListOf<String>()
    private val service =
        SendMessageService(
            DeliveryKeyAuthenticator(mailboxes),
            inbox,
            MessageNotifier { mailboxId -> notified += mailboxId },
            Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
        )

    private fun send(
        envelope: ByteArray = ByteArray(10) { 1 },
        ttl: Long? = null,
        deliveryKey: ByteArray = DeliveryKeyTestSupport.deliveryKey,
        mailboxId: String = id,
    ) = service.send(mailboxId, deliveryKey, envelope, ttl)

    private fun rejected(reason: SendRejection) = SendResult.Rejected(reason)

    private fun stored() = inbox.messagesAfter(id, 0)

    @Test
    fun `an accepted message is stored with the server time and the default ttl`() {
        val envelope = ByteArray(10) { 7 }

        assertEquals(SendResult.Accepted(now), send(envelope = envelope))

        val message = stored().single()
        assertEquals(1L, message.serverSeq)
        assertEquals(now, message.serverTsMillis)
        assertEquals(now + thirtyDaysMillis, message.expiresAtMillis)
        assertContentEquals(envelope, message.envelope)
    }

    @Test
    fun `a ttl is honoured and a longer one is clamped to 30 days`() {
        send(ttl = 60L)
        send(ttl = Long.MAX_VALUE)

        assertEquals(listOf(now + 60_000L, now + thirtyDaysMillis), stored().map { it.expiresAtMillis })
    }

    @Test
    fun `a ttl below one and an empty envelope are rejected`() {
        assertEquals(rejected(SendRejection.INVALID_REQUEST), send(ttl = 0L))
        assertEquals(rejected(SendRejection.INVALID_REQUEST), send(ttl = -5L))
        assertEquals(rejected(SendRejection.INVALID_REQUEST), send(envelope = ByteArray(0)))
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `an envelope over 64 KiB is rejected and exactly 64 KiB is accepted`() {
        assertEquals(rejected(SendRejection.TOO_LARGE), send(envelope = ByteArray(65_537)))
        assertEquals(SendResult.Accepted(now), send(envelope = ByteArray(65_536)))
    }

    @Test
    fun `a wrong key and an unknown mailbox are unauthorised and store nothing`() {
        assertEquals(rejected(SendRejection.UNAUTHORIZED), send(deliveryKey = ByteArray(16) { 4 }))
        assertEquals(rejected(SendRejection.UNAUTHORIZED), send(mailboxId = "mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `authorisation is checked before the request is validated`() {
        val result = send(envelope = ByteArray(0), deliveryKey = ByteArray(16) { 4 })

        assertEquals(rejected(SendRejection.UNAUTHORIZED), result)
    }

    @Test
    fun `a full mailbox is rejected`() {
        repeat(1_000) { inbox.append(id, NewMessage(ByteArray(1), now, now + 86_400_000L), 1_000) }

        assertEquals(rejected(SendRejection.QUOTA_EXCEEDED), send())
    }

    @Test
    fun `messages receive consecutive sequence numbers`() {
        send()
        send()

        assertEquals(listOf(1L, 2L), stored().map { it.serverSeq })
    }

    @Test
    fun `an accepted message notifies its mailbox once`() {
        send()

        assertEquals(listOf(id), notified)
    }

    @Test
    fun `rejected messages notify nobody`() {
        send(ttl = 0L)
        send(deliveryKey = ByteArray(16) { 4 })
        send(envelope = ByteArray(65_537))

        assertTrue(notified.isEmpty())
    }
}
