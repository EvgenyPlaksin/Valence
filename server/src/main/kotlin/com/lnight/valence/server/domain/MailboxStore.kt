package com.lnight.valence.server.domain

interface MailboxStore {
    fun create(
        mailbox: Mailbox,
        oneTimePreKeys: List<OneTimePreKey>,
    ): Boolean

    fun find(mailboxId: String): Mailbox?
}
