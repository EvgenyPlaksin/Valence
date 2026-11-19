package com.lnight.valence.server

import com.lnight.valence.server.domain.AppendResult
import com.lnight.valence.server.domain.InboxPurger
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.store.InMemoryInboxStore
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PurgeTest {
    private val first = RegistrationTestSupport.MAILBOX_ID
    private val second = "mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa"

    private fun InMemoryInboxStore.add(
        mailboxId: String,
        expiresAt: Long,
    ) = append(mailboxId, NewMessage(ByteArray(1), 1_000L, expiresAt), 1_000)

    @Test
    fun `purging removes expired messages in every mailbox and reports the count`() {
        val store = InMemoryInboxStore()
        store.add(first, 5_000L)
        store.add(first, 5_000L)
        store.add(first, 100_000L)
        store.add(second, 5_000L)
        assertEquals(3, store.purgeExpired(5_000L))

        assertEquals(listOf(3L), store.messagesAfter(first, 0).map { it.serverSeq })
        assertEquals(emptyList(), store.messagesAfter(second, 0))
    }

    @Test
    fun `messages that have not expired are kept`() {
        val store = InMemoryInboxStore()
        store.add(first, 5_000L)

        assertEquals(0, store.purgeExpired(4_999L))
        assertEquals(1, store.messagesAfter(first, 0).size)
    }

    @Test
    fun `purged sequence numbers are never reused`() {
        val store = InMemoryInboxStore()
        store.add(first, 5_000L)
        store.purgeExpired(5_000L)

        val result = store.add(first, 100_000L)

        assertEquals(2L, assertIs<AppendResult.Appended>(result).serverSeq)
    }

    @Test
    fun `the purger uses the clock`() {
        val store = InMemoryInboxStore()
        store.add(first, 5_000L)

        assertEquals(1, InboxPurger(store, TestClock(5_000L)).purge())
    }

    @Test
    fun `the schedule purges expired messages periodically`() =
        testApplication {
            val store = InMemoryInboxStore()
            store.add(first, 5_000L)
            application { schedulePurge(InboxPurger(store, TestClock(5_000L)), 20.milliseconds) }

            startApplication()

            withTimeout(5.seconds) { while (store.messagesAfter(first, 0).isNotEmpty()) delay(10) }
        }
}
