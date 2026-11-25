package com.lnight.valence.server.store

import com.lnight.valence.server.domain.KeyStoreOutcome
import com.lnight.valence.server.domain.Mailbox
import com.lnight.valence.server.domain.MailboxStore
import com.lnight.valence.server.domain.OneTimeKyberPreKey
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.PreKeyBundle
import com.lnight.valence.server.domain.PreKeyCounts
import com.lnight.valence.server.domain.SignedPublicKey
import java.util.concurrent.ConcurrentHashMap

class InMemoryMailboxStore : MailboxStore {
    private val mailboxes = ConcurrentHashMap<String, StoredMailbox>()

    override suspend fun create(
        mailbox: Mailbox,
        oneTimePreKeys: List<OneTimePreKey>,
        oneTimeKyberPreKeys: List<OneTimeKyberPreKey>,
    ): Boolean = mailboxes.putIfAbsent(mailbox.id, StoredMailbox(mailbox, oneTimePreKeys, oneTimeKyberPreKeys)) == null

    override suspend fun find(mailboxId: String): Mailbox? = mailboxes[mailboxId]?.mailbox

    override suspend fun oneTimePreKeyCount(mailboxId: String): Int? = mailboxes[mailboxId]?.oneTimePreKeys?.size

    override suspend fun oneTimeKyberPreKeyCount(mailboxId: String): Int? =
        mailboxes[mailboxId]?.oneTimeKyberPreKeys?.size

    override suspend fun preKeyCounts(mailboxId: String): PreKeyCounts? =
        mailboxes[mailboxId]?.let { PreKeyCounts(it.oneTimePreKeys.size, it.oneTimeKyberPreKeys.size) }

    override suspend fun replaceSignedPreKeys(
        mailboxId: String,
        signedPreKey: SignedPublicKey,
        kyberPreKey: SignedPublicKey,
    ): KeyStoreOutcome =
        update(mailboxId) { stored ->
            if (
                stored.mailbox.signedPreKey.id == signedPreKey.id ||
                stored.mailbox.kyberPreKey.id == kyberPreKey.id ||
                stored.oneTimeKyberPreKeys.any { it.id == kyberPreKey.id }
            ) {
                Change(stored, KeyStoreOutcome.ID_EXISTS)
            } else {
                val mailbox = stored.mailbox.withSignedPreKeys(signedPreKey, kyberPreKey)
                Change(
                    StoredMailbox(mailbox, stored.oneTimePreKeys, stored.oneTimeKyberPreKeys),
                    KeyStoreOutcome.UPDATED,
                )
            }
        }

    override suspend fun addOneTimePreKeys(
        mailboxId: String,
        keys: List<OneTimePreKey>,
        maxStored: Int,
    ): KeyStoreOutcome =
        update(mailboxId) { stored ->
            val knownIds = stored.oneTimePreKeys.map { it.id }.toSet()
            when {
                keys.any { it.id in knownIds } -> {
                    Change(stored, KeyStoreOutcome.ID_EXISTS)
                }

                stored.oneTimePreKeys.size + keys.size > maxStored -> {
                    Change(stored, KeyStoreOutcome.LIMIT_EXCEEDED)
                }

                else -> {
                    Change(
                        StoredMailbox(stored.mailbox, stored.oneTimePreKeys + keys, stored.oneTimeKyberPreKeys),
                        KeyStoreOutcome.UPDATED,
                    )
                }
            }
        }

    override suspend fun addOneTimeKyberPreKeys(
        mailboxId: String,
        keys: List<OneTimeKyberPreKey>,
        maxStored: Int,
    ): KeyStoreOutcome =
        update(mailboxId) { stored ->
            val knownIds = stored.oneTimeKyberPreKeys.map { it.id }.toSet()
            when {
                keys.any { it.id == stored.mailbox.kyberPreKey.id || it.id in knownIds } -> {
                    Change(stored, KeyStoreOutcome.ID_EXISTS)
                }

                stored.oneTimeKyberPreKeys.size + keys.size > maxStored -> {
                    Change(stored, KeyStoreOutcome.LIMIT_EXCEEDED)
                }

                else -> {
                    Change(
                        StoredMailbox(stored.mailbox, stored.oneTimePreKeys, stored.oneTimeKyberPreKeys + keys),
                        KeyStoreOutcome.UPDATED,
                    )
                }
            }
        }

    override suspend fun takeBundle(mailboxId: String): PreKeyBundle? {
        var bundle: PreKeyBundle? = null
        mailboxes.computeIfPresent(mailboxId) { _, stored ->
            val mailbox = stored.mailbox
            bundle =
                PreKeyBundle(
                    identityKey = mailbox.identityKey,
                    signedPreKey = mailbox.signedPreKey,
                    kyberPreKey = mailbox.kyberPreKey,
                    oneTimePreKey = stored.oneTimePreKeys.firstOrNull(),
                    oneTimeKyberPreKey = stored.oneTimeKyberPreKeys.firstOrNull(),
                )
            StoredMailbox(mailbox, stored.oneTimePreKeys.drop(1), stored.oneTimeKyberPreKeys.drop(1))
        }
        return bundle
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
        val oneTimeKyberPreKeys: List<OneTimeKyberPreKey>,
    )

    private class Change(
        val stored: StoredMailbox,
        val outcome: KeyStoreOutcome,
    )
}
