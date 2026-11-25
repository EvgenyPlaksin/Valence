package com.lnight.valence.server.store

import com.lnight.valence.server.domain.AppendResult
import com.lnight.valence.server.domain.ConfirmResult
import com.lnight.valence.server.domain.NewMessage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InMemoryInboxStoreReceiveTest {
    private val id = "mbx_g4r2k7x5qmwv6bnzdcs3n4pe5a"

    private suspend fun store(
        count: Int,
        expiresAt: Long = 100_000L,
    ) = InMemoryInboxStore().also { store ->
        repeat(count) { store.append(id, ByteArray(32), NewMessage(ByteArray(1), 1_000L, expiresAt), 1_000) }
    }

    private suspend fun InMemoryInboxStore.sequence() = messagesAfter(id, 0).map { it.serverSeq }

    @Test
    fun `a read can be limited to a batch`() =
        runTest {
            assertEquals(listOf(2L, 3L), store(5).messagesAfter(id, 1, limit = 2).map { it.serverSeq })
        }

    @Test
    fun `expired messages are not returned`() =
        runTest {
            val store = store(3, expiresAt = 5_000L)

            assertEquals(3, store.messagesAfter(id, 0, nowMillis = 4_999L).size)
            assertEquals(0, store.messagesAfter(id, 0, nowMillis = 5_000L).size)
        }

    @Test
    fun `deleting up to a number removes that number and everything below`() =
        runTest {
            val store = store(5)

            assertEquals(ConfirmResult.CONFIRMED, store.confirm(id, 0, 3))

            assertEquals(listOf(4L, 5L), store.sequence())
        }

    @Test
    fun `deleting is idempotent and lower values change nothing`() =
        runTest {
            val store = store(5)

            assertEquals(ConfirmResult.CONFIRMED, store.confirm(id, 0, 3))
            assertEquals(ConfirmResult.CONFIRMED, store.confirm(id, 0, 1))
            assertEquals(ConfirmResult.CONFIRMED, store.confirm(id, 0, 3))

            assertEquals(listOf(4L, 5L), store.sequence())
        }

    @Test
    fun `deleting from an unknown mailbox does nothing`() =
        runTest {
            assertEquals(ConfirmResult.STALE, InMemoryInboxStore().confirm(id, 0, 10))
        }

    @Test
    fun `numbers are not reused after deletion`() =
        runTest {
            val store = store(3)
            store.confirm(id, 0, 3)

            val result = store.append(id, ByteArray(32), NewMessage(ByteArray(1), 1_000L, 100_000L), 1_000)

            assertEquals(4L, assertIs<AppendResult.Appended>(result).serverSeq)
        }
}
