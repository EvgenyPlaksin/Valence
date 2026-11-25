package com.lnight.valence.server.domain

import java.util.concurrent.ConcurrentHashMap

class MailboxSubscriptions : MessageNotifier {
    private val subscriptions = ConcurrentHashMap<String, Subscription>()

    @Volatile
    private var shuttingDown = false

    fun subscribe(
        mailboxId: String,
        generation: Long,
    ): Subscription {
        val subscription = Subscription(generation)
        subscriptions.put(mailboxId, subscription)?.supersede()
        if (shuttingDown) subscription.shutDown()
        return subscription
    }

    fun unsubscribe(
        mailboxId: String,
        subscription: Subscription,
    ) {
        subscriptions.remove(mailboxId, subscription)
    }

    override fun messageStored(mailboxId: String) {
        subscriptions[mailboxId]?.wake?.trySend(Unit)
    }

    fun supersedeOlder(
        mailboxId: String,
        generation: Long,
    ) {
        subscriptions[mailboxId]
            ?.takeIf { it.generation < generation }
            ?.supersede()
    }

    fun wakeIfCurrent(
        mailboxId: String,
        generation: Long,
    ) {
        subscriptions[mailboxId]
            ?.takeIf { it.generation == generation }
            ?.wake
            ?.trySend(Unit)
    }

    fun activeMailboxIds(): Set<String> = subscriptions.keys.toSet()

    fun activeCount(): Int = subscriptions.size

    fun shutdown() {
        shuttingDown = true
        subscriptions.values.forEach { it.shutDown() }
    }
}
