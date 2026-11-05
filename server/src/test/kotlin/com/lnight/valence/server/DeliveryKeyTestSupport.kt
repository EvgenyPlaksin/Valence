package com.lnight.valence.server

import com.lnight.valence.server.domain.Mailbox
import java.security.MessageDigest

internal object DeliveryKeyTestSupport {
    val deliveryKey = ByteArray(16) { 3 }
    val deliveryKeyHash: ByteArray = MessageDigest.getInstance("SHA-256").digest(deliveryKey)

    fun mailbox(): Mailbox = RegistrationTestSupport.mailbox(deliveryKeyHash = deliveryKeyHash)
}
