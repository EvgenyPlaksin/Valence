package com.lnight.valence.protocol

import java.nio.ByteBuffer

object SignedMessages {
    private const val REGISTER_TAG = "valence/register/v1"
    private const val LOGIN_TAG = "valence/login/v1"

    fun register(
        challenge: ByteArray,
        mailboxId: String,
        identityKey: ByteArray,
        deliveryKeyHash: ByteArray,
    ): ByteArray =
        encode(
            REGISTER_TAG.toByteArray(Charsets.UTF_8),
            challenge,
            mailboxId.toByteArray(Charsets.UTF_8),
            identityKey,
            deliveryKeyHash,
        )

    fun login(
        challenge: ByteArray,
        mailboxId: String,
    ): ByteArray =
        encode(
            LOGIN_TAG.toByteArray(Charsets.UTF_8),
            challenge,
            mailboxId.toByteArray(Charsets.UTF_8),
        )

    private fun encode(vararg fields: ByteArray): ByteArray =
        fields.fold(ByteArray(0)) { message, field -> message + lengthPrefix(field.size) + field }

    private fun lengthPrefix(size: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(size).array()
}
