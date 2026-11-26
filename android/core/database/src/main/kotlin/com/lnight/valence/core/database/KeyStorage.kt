package com.lnight.valence.core.database

enum class KyberPreKeyRole {
    LastResort,
    OneTime,
}

data class IdentityKeyStorage(
    val publicKey: ImmutableBytes,
    val wrappedPrivateKey: ImmutableBytes,
    val wrapIv: ImmutableBytes,
    val keyStoreAlias: String,
    val createdAtMillis: Long,
)

data class SignedPreKeyStorage(
    val preKeyId: Int,
    val publicKey: ImmutableBytes,
    val wrappedPrivateKey: ImmutableBytes,
    val wrapIv: ImmutableBytes,
    val signature: ImmutableBytes,
    val createdAtMillis: Long,
    val retiredAtMillis: Long?,
)

data class KyberPreKeyStorage(
    val preKeyId: Int,
    val publicKey: ImmutableBytes,
    val wrappedPrivateKey: ImmutableBytes,
    val wrapIv: ImmutableBytes,
    val signature: ImmutableBytes,
    val role: KyberPreKeyRole,
    val uploaded: Boolean,
    val createdAtMillis: Long,
    val retiredAtMillis: Long?,
)

data class OneTimePreKeyStorage(
    val preKeyId: Int,
    val publicKey: ImmutableBytes,
    val wrappedPrivateKey: ImmutableBytes,
    val wrapIv: ImmutableBytes,
    val uploaded: Boolean,
)

data class ContactKeyStorage(
    val contactId: String,
    val identityKey: ImmutableBytes,
    val deliveryKey: ImmutableBytes,
    val safetyNumberHash: ImmutableBytes?,
)

data class DeliveryKeyStorage(
    val keyBytes: ImmutableBytes,
    val createdAtMillis: Long,
)

data class SenderCertificateStorage(
    val certificate: ImmutableBytes,
    val expiresAtMillis: Long,
)
