package com.lnight.valence.server

import com.lnight.valence.server.domain.MailboxSubscriptions
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailboxSubscriptionsTest {
    private val id = RegistrationTestSupport.MAILBOX_ID
    private val other = "mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val subscriptions = MailboxSubscriptions()

    @Test
    fun `a stored message wakes the subscription of its mailbox`() {
        val subscription = subscriptions.subscribe(id)

        subscriptions.messageStored(id)

        assertTrue(subscription.wake.tryReceive().isSuccess)
    }

    @Test
    fun `other mailboxes are not woken`() {
        val subscription = subscriptions.subscribe(id)

        subscriptions.messageStored(other)

        assertTrue(subscription.wake.tryReceive().isFailure)
    }

    @Test
    fun `wake ups are conflated`() {
        val subscription = subscriptions.subscribe(id)

        repeat(3) { subscriptions.messageStored(id) }

        assertTrue(subscription.wake.tryReceive().isSuccess)
        assertTrue(subscription.wake.tryReceive().isFailure)
    }

    @Test
    fun `a newer subscription supersedes the older one`() {
        val older = subscriptions.subscribe(id)
        val newer = subscriptions.subscribe(id)

        subscriptions.messageStored(id)

        assertTrue(older.superseded.isCompleted)
        assertFalse(newer.superseded.isCompleted)
        assertTrue(older.wake.tryReceive().isFailure)
        assertTrue(newer.wake.tryReceive().isSuccess)
    }

    @Test
    fun `unsubscribing an old subscription keeps the newer one`() {
        val older = subscriptions.subscribe(id)
        val newer = subscriptions.subscribe(id)

        subscriptions.unsubscribe(id, older)
        subscriptions.messageStored(id)

        assertTrue(newer.wake.tryReceive().isSuccess)
    }

    @Test
    fun `an unsubscribed mailbox is no longer woken`() {
        val subscription = subscriptions.subscribe(id)

        subscriptions.unsubscribe(id, subscription)
        subscriptions.messageStored(id)

        assertTrue(subscription.wake.tryReceive().isFailure)
    }

    @Test
    fun `a stored message without a subscriber is harmless`() {
        subscriptions.messageStored(id)
    }
}
