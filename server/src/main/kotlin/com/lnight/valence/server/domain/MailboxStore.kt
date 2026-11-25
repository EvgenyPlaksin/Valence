package com.lnight.valence.server.domain

interface MailboxStore {
    suspend fun create(
        mailbox: Mailbox,
        oneTimePreKeys: List<OneTimePreKey>,
        oneTimeKyberPreKeys: List<OneTimeKyberPreKey> = emptyList(),
    ): Boolean

    suspend fun find(mailboxId: String): Mailbox?

    suspend fun oneTimePreKeyCount(mailboxId: String): Int?

    suspend fun oneTimeKyberPreKeyCount(mailboxId: String): Int?

    suspend fun preKeyCounts(mailboxId: String): PreKeyCounts?

    suspend fun replaceSignedPreKeys(
        mailboxId: String,
        signedPreKey: SignedPublicKey,
        kyberPreKey: SignedPublicKey,
    ): KeyStoreOutcome

    suspend fun addOneTimePreKeys(
        mailboxId: String,
        keys: List<OneTimePreKey>,
        maxStored: Int,
    ): KeyStoreOutcome

    suspend fun addOneTimeKyberPreKeys(
        mailboxId: String,
        keys: List<OneTimeKyberPreKey>,
        maxStored: Int,
    ): KeyStoreOutcome

    suspend fun takeBundle(mailboxId: String): PreKeyBundle?
}
