package com.lnight.valence.server.store

import com.lnight.valence.server.domain.KeyStoreOutcome
import com.lnight.valence.server.domain.Mailbox
import com.lnight.valence.server.domain.MailboxStore
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.SignedPublicKey
import java.util.concurrent.ConcurrentHashMap

class InMemoryMailboxStore : MailboxStore {
    private val mailboxes = ConcurrentHashMap<String, StoredMailbox>()

    override fun create(
        mailbox: Mailbox,
        oneTimePreKeys: List<OneTimePreKey>,
    ): Boolean = mailboxes.putIfAbsent(mailbox.id, StoredMailbox(mailbox, oneTimePreKeys)) == null

    override fun find(mailboxId: String): Mailbox? = mailboxes[mailboxId]?.mailbox

    override fun oneTimePreKeyCount(mailboxId: String): Int? = mailboxes[mailboxId]?.oneTimePreKeys?.size

    override fun replaceSignedPreKeys(
        mailboxId: String,
        signedPreKey: SignedPublicKey,
        kyberPreKey: SignedPublicKey,
    ): KeyStoreOutcome =
        update(mailboxId) { stored ->
            if (stored.mailbox.signedPreKey.id == signedPreKey.id || stored.mailbox.kyberPreKey.id == kyberPreKey.id) {
                Change(stored, KeyStoreOutcome.ID_EXISTS)
            } else {
                val mailbox = stored.mailbox.withSignedPreKeys(signedPreKey, kyberPreKey)
                Change(StoredMailbox(mailbox, stored.oneTimePreKeys), KeyStoreOutcome.UPDATED)
            }
        }

    override fun addOneTimePreKeys(
        mailboxId: String,
        keys: List<OneTimePreKey>,
        maxStored: Int,
    ): KeyStoreOutcome =
        update(mailboxId) { stored ->
            val knownIds = stored.oneTimePreKeys.map { it.id }.toSet()
            when {
                keys.any { it.id in knownIds } -> Change(stored, KeyStoreOutcome.ID_EXISTS)
                stored.oneTimePreKeys.size + keys.size > maxStored -> Change(stored, KeyStoreOutcome.LIMIT_EXCEEDED)
                else -> Change(StoredMailbox(stored.mailbox, stored.oneTimePreKeys + keys), KeyStoreOutcome.UPDATED)
            }
        }

    private fun update(
        mailboxId: String,
        change: (StoredMailbox) -> Change,
    ): KeyStoreOutcome {
        var outcome = KeyStoreOutcome.UNKNOWN_MAILBOX
        mailboxes.computeIfPresent(mailboxId) { _, stored ->
            val result = change(stored)
            outcome = result.outcome
            result.stored
        }
        return outcome
    }

    private class StoredMailbox(
        val mailbox: Mailbox,
        val oneTimePreKeys: List<OneTimePreKey>,
    )

    private class Change(
        val stored: StoredMailbox,
        val outcome: KeyStoreOutcome,
    )
}
