package com.lnight.valence.server.store

import com.lnight.valence.server.domain.AppendResult
import com.lnight.valence.server.domain.NewMessage
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InMemoryInboxStoreTest {
    private val id = "mbx_g4r2k7x5qmwv6bnzdcs3n4pe5a"
    private val other = "mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa"

    private fun message(
        at: Long = 1_000L,
        expiresAt: Long = 100_000L,
        fill: Byte = 1,
    ) = NewMessage(ByteArray(4) { fill }, at, expiresAt)

    private fun InMemoryInboxStore.add(
        mailboxId: String = id,
        message: NewMessage = message(),
        maxQueued: Int = 1_000,
    ): AppendResult = append(mailboxId, message, maxQueued)

    private fun seq(result: AppendResult) = assertIs<AppendResult.Appended>(result).serverSeq

    @Test
    fun `sequence numbers start at 1 and increase`() {
        val store = InMemoryInboxStore()

        assertEquals(listOf(1L, 2L, 3L), (1..3).map { seq(store.add()) })
    }

    @Test
    fun `mailboxes have independent sequences`() {
        val store = InMemoryInboxStore()
        store.add()
        store.add()

        assertEquals(1L, seq(store.add(mailboxId = other)))
    }

    @Test
    fun `messages after a number are returned in ascending order and exclude that number`() {
        val store = InMemoryInboxStore()
        (1..4).forEach { store.add(message = message(fill = it.toByte())) }

        val messages = store.messagesAfter(id, 2)

        assertEquals(listOf(3L, 4L), messages.map { it.serverSeq })
        assertContentEquals(ByteArray(4) { 3 }, messages.first().envelope)
        assertEquals(emptyList(), store.messagesAfter(other, 0))
    }

    @Test
    fun `a full mailbox is rejected without consuming a number`() {
        val store = InMemoryInboxStore()
        val shortLived = message(at = 1_000L, expiresAt = 5_000L)

        store.add(message = shortLived, maxQueued = 2)
        store.add(message = shortLived, maxQueued = 2)

        assertEquals(AppendResult.QuotaExceeded, store.add(message = message(at = 2_000L), maxQueued = 2))
        assertEquals(3L, seq(store.add(message = message(at = 5_000L), maxQueued = 2)))
    }

    @Test
    fun `expired messages are dropped and their numbers are never reused`() {
        val store = InMemoryInboxStore()
        store.add(message = message(at = 1_000L, expiresAt = 5_000L))

        store.add(message = message(at = 5_000L))

        assertEquals(listOf(2L), store.messagesAfter(id, 0).map { it.serverSeq })
    }

    @Test
    fun `concurrent appends receive unique sequence numbers`() {
        val store = InMemoryInboxStore()
        val executor = Executors.newFixedThreadPool(8)

        val numbers = (1..200).map { executor.submit(Callable { seq(store.add()) }) }.map { it.get() }
        executor.shutdown()

        assertEquals((1L..200L).toList(), numbers.sorted())
    }
}
