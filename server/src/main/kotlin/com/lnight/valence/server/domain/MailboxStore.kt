package com.lnight.valence.server.domain

interface MailboxStore {
    fun create(
        mailbox: Mailbox,
        oneTimePreKeys: List<OneTimePreKey>,
    ): Boolean

    fun find(mailboxId: String): Mailbox?

    fun oneTimePreKeyCount(mailboxId: String): Int?

    fun replaceSignedPreKeys(
        mailboxId: String,
        signedPreKey: SignedPublicKey,
        kyberPreKey: SignedPublicKey,
    ): KeyStoreOutcome

    fun addOneTimePreKeys(
        mailboxId: String,
        keys: List<OneTimePreKey>,
        maxStored: Int,
    ): KeyStoreOutcome

    fun takeBundle(mailboxId: String): PreKeyBundle?
}
