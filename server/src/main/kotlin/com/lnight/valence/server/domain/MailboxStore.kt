package com.lnight.valence.server.domain

interface MailboxStore {
    suspend fun create(
        mailbox: Mailbox,
        oneTimePreKeys: List<OneTimePreKey>,
    ): Boolean

    suspend fun find(mailboxId: String): Mailbox?

    suspend fun oneTimePreKeyCount(mailboxId: String): Int?

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

    suspend fun takeBundle(mailboxId: String): PreKeyBundle?
}
