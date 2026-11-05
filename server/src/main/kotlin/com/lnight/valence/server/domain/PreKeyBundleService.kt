package com.lnight.valence.server.domain

class PreKeyBundleService(
    private val authenticator: DeliveryKeyAuthenticator,
    private val store: MailboxStore,
) {
    fun fetch(
        mailboxId: String,
        deliveryKey: ByteArray,
    ): PreKeyBundle? = authenticator.authenticate(mailboxId, deliveryKey)?.let { store.takeBundle(it.id) }
}
