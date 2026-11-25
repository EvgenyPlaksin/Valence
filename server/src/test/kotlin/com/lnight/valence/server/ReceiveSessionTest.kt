package com.lnight.valence.server

import com.lnight.valence.server.domain.ConfirmResult
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.domain.ReceiveSession
import com.lnight.valence.server.store.InMemoryInboxStore
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiveSessionTest {
    private val id = RegistrationTestSupport.MAILBOX_ID
    private val now = ChallengeTestSupport.ISSUED_AT_MILLIS
    private val inbox = InMemoryInboxStore()
    private val session = ReceiveSession(id, 0, inbox, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC))

    private suspend fun add(
        count: Int = 1,
        expiresAt: Long = now + 60_000L,
    ) = repeat(count) { inbox.append(id, ByteArray(32), NewMessage(ByteArray(1), now, expiresAt), 1_000) }

    private suspend fun batch() = session.nextBatch().map { it.serverSeq }

    private suspend fun stored() = inbox.messagesAfter(id, 0).map { it.serverSeq }

    @Test
    fun `nothing is delivered before the client resumes`() =
        runTest {
            add(2)

            assertEquals(emptyList(), batch())
        }

    @Test
    fun `resuming delivers the stored messages in order and then nothing`() =
        runTest {
            add(3)

            assertTrue(session.resume(0))
            assertEquals(listOf(1L, 2L, 3L), batch())
            assertEquals(emptyList(), batch())
        }

    @Test
    fun `resuming after a number skips what the client has`() =
        runTest {
            add(3)
            session.resume(2)

            assertEquals(listOf(3L), batch())
        }

    @Test
    fun `messages stored later are delivered by the next read`() =
        runTest {
            add(1)
            session.resume(0)
            batch()

            add(1)

            assertEquals(listOf(2L), batch())
        }

    @Test
    fun `a second or a negative resume is rejected`() =
        runTest {
            assertFalse(ReceiveSession(id, 0, inbox, Clock.systemUTC()).resume(-1))
            assertTrue(session.resume(0))
            assertFalse(session.resume(0))
        }

    @Test
    fun `reads come in batches of 100`() =
        runTest {
            add(250)
            session.resume(0)

            assertEquals(listOf(100, 100, 50, 0), List(4) { session.nextBatch().size })
        }

    @Test
    fun `expired messages are skipped`() =
        runTest {
            add(2, expiresAt = now)
            add(1)
            session.resume(0)

            assertEquals(listOf(3L), batch())
        }

    @Test
    fun `a confirm before resume is rejected and deletes nothing`() =
        runTest {
            add(2)

            assertEquals(ConfirmResult.INVALID, session.confirm(2))
            assertEquals(listOf(1L, 2L), stored())
        }

    @Test
    fun `a confirm deletes up to the number and is idempotent`() =
        runTest {
            add(3)
            session.resume(0)
            batch()

            assertEquals(ConfirmResult.CONFIRMED, session.confirm(2))
            assertEquals(ConfirmResult.CONFIRMED, session.confirm(1))
            assertEquals(listOf(3L), stored())
            assertEquals(ConfirmResult.INVALID, session.confirm(-1))
        }

    @Test
    fun `a confirm is clamped to what has been sent`() =
        runTest {
            add(150)
            session.resume(0)
            batch()

            assertEquals(ConfirmResult.CONFIRMED, session.confirm(150))
            assertEquals((101L..150L).toList(), stored())
        }

    @Test
    fun `a stale session cannot confirm messages after a newer socket connects`() =
        runTest {
            add(2)
            session.resume(0)
            session.nextBatch()
            inbox.advanceSocketGeneration(id)

            assertEquals(ConfirmResult.STALE, session.confirm(2))
            assertEquals(listOf(1L, 2L), stored())
        }

    @Test
    fun `polling reports pending messages and the current generation`() =
        runTest {
            add(1)
            session.resume(0)

            assertEquals(0L, session.checkForUpdates()?.generation)
            assertEquals(true, session.checkForUpdates()?.hasMessages)
        }
}
