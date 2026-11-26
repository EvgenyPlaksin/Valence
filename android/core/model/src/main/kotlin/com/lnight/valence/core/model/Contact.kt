package com.lnight.valence.core.model

data class Contact(
    val id: ContactId,
    val mailboxId: MailboxId,
    val displayName: String,
    val createdAtMillis: Long,
)
