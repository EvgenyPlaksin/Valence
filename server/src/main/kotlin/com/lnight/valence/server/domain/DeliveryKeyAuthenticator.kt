package com.lnight.valence.server.domain

import java.security.MessageDigest

class DeliveryKeyAuthenticator(
    private val store: MailboxStore,
) {
    fun authenticate(
        mailboxId: String,
        deliveryKey: ByteArray,
    ): Mailbox? {
        val presentedHash = MessageDigest.getInstance(HASH_ALGORITHM).digest(deliveryKey)
        val mailbox = store.find(mailboxId)
        val storedHash = mailbox?.deliveryKeyHash ?: ByteArray(WireSizes.HASH)
        val matches = MessageDigest.isEqual(presentedHash, storedHash)
        return mailbox?.takeIf { matches }
    }

    private companion object {
        const val HASH_ALGORITHM = "SHA-256"
    }
}
