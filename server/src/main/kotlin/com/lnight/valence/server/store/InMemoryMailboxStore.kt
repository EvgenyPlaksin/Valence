package com.lnight.valence.server.store

import com.lnight.valence.server.domain.Mailbox
import com.lnight.valence.server.domain.MailboxStore
import com.lnight.valence.server.domain.OneTimePreKey
import java.util.concurrent.ConcurrentHashMap

class InMemoryMailboxStore : MailboxStore {
    private val mailboxes = ConcurrentHashMap<String, StoredMailbox>()

    override fun create(
        mailbox: Mailbox,
        oneTimePreKeys: List<OneTimePreKey>,
    ): Boolean = mailboxes.putIfAbsent(mailbox.id, StoredMailbox(mailbox, oneTimePreKeys)) == null

    override fun find(mailboxId: String): Mailbox? = mailboxes[mailboxId]?.mailbox

    private class StoredMailbox(
        val mailbox: Mailbox,
        val oneTimePreKeys: List<OneTimePreKey>,
    )
}
