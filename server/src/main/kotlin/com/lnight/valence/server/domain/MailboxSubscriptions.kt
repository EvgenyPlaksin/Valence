package com.lnight.valence.server.domain

import java.util.concurrent.ConcurrentHashMap

class MailboxSubscriptions : MessageNotifier {
    private val subscriptions = ConcurrentHashMap<String, Subscription>()

    fun subscribe(mailboxId: String): Subscription {
        val subscription = Subscription()
        subscriptions.put(mailboxId, subscription)?.supersede()
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
}
