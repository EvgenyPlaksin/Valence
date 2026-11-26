package com.lnight.valence.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "identity_key")
internal data class IdentityKeyEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "public_key") val publicKey: ByteArray,
    @ColumnInfo(name = "wrapped_private") val wrappedPrivateKey: ByteArray,
    @ColumnInfo(name = "wrap_iv") val wrapIv: ByteArray,
    @ColumnInfo(name = "keystore_alias") val keyStoreAlias: String,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
)

@Entity(tableName = "signed_prekey")
internal data class SignedPreKeyEntity(
    @PrimaryKey @ColumnInfo(name = "prekey_id") val preKeyId: Int,
    @ColumnInfo(name = "public_key") val publicKey: ByteArray,
    @ColumnInfo(name = "wrapped_private") val wrappedPrivateKey: ByteArray,
    @ColumnInfo(name = "wrap_iv") val wrapIv: ByteArray,
    val signature: ByteArray,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    @ColumnInfo(name = "retired_at") val retiredAtMillis: Long?,
)

@Entity(tableName = "kyber_prekey")
internal data class KyberPreKeyEntity(
    @PrimaryKey @ColumnInfo(name = "prekey_id") val preKeyId: Int,
    @ColumnInfo(name = "public_key") val publicKey: ByteArray,
    @ColumnInfo(name = "wrapped_private") val wrappedPrivateKey: ByteArray,
    @ColumnInfo(name = "wrap_iv") val wrapIv: ByteArray,
    val signature: ByteArray,
    val role: Int,
    val uploaded: Int,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    @ColumnInfo(name = "retired_at") val retiredAtMillis: Long?,
)

@Entity(tableName = "one_time_prekey")
internal data class OneTimePreKeyEntity(
    @PrimaryKey @ColumnInfo(name = "prekey_id") val preKeyId: Int,
    @ColumnInfo(name = "public_key") val publicKey: ByteArray,
    @ColumnInfo(name = "wrapped_private") val wrappedPrivateKey: ByteArray,
    @ColumnInfo(name = "wrap_iv") val wrapIv: ByteArray,
    val uploaded: Int,
)

@Entity(
    tableName = "contact",
    indices = [Index(value = ["mailbox_id"], unique = true)],
)
internal data class ContactEntity(
    @PrimaryKey @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "mailbox_id") val mailboxId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "identity_key") val identityKey: ByteArray,
    @ColumnInfo(name = "delivery_key") val deliveryKey: ByteArray,
    @ColumnInfo(name = "trust_state") val trustState: Int = 0,
    @ColumnInfo(name = "safety_number_hash") val safetyNumberHash: ByteArray?,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
)

@Entity(
    tableName = "signal_session",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contact_id"],
            childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SignalSessionEntity(
    @PrimaryKey @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "record_blob") val recordBlob: ByteArray,
    @ColumnInfo(name = "record_iv") val recordIv: ByteArray,
    @ColumnInfo(name = "record_version") val recordVersion: Int,
    @ColumnInfo(name = "updated_at") val updatedAtMillis: Long,
)

@Entity(tableName = "delivery_key")
internal data class DeliveryKeyEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "key_bytes") val keyBytes: ByteArray,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
)

@Entity(tableName = "sender_certificate")
internal data class SenderCertificateEntity(
    @PrimaryKey val id: Int = 1,
    val certificate: ByteArray,
    @ColumnInfo(name = "expires_at") val expiresAtMillis: Long,
)

@Entity(
    tableName = "conversation",
    indices = [
        Index(
            value = ["pinned", "last_message_at"],
            name = "idx_conversation_recent",
            orders = [Index.Order.DESC, Index.Order.DESC],
        ),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contact_id"],
            childColumns = ["contact_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ConversationEntity(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "contact_id") val contactId: String,
    @ColumnInfo(name = "last_message_id") val lastMessageId: String?,
    @ColumnInfo(name = "last_message_at") val lastMessageAtMillis: Long?,
    @ColumnInfo(name = "unread_count") val unreadCount: Int = 0,
    val pinned: Int = 0,
    @ColumnInfo(name = "muted_until") val mutedUntilMillis: Long?,
)

@Entity(
    tableName = "message",
    indices = [
        Index(
            value = ["conversation_id", "sent_at", "message_id"],
            name = "idx_message_timeline",
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC],
        ),
        Index(value = ["server_seq"], unique = true, name = "idx_message_server_seq"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["conversation_id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class MessageEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val direction: Int,
    val status: Int,
    val kind: Int,
    val body: String?,
    @ColumnInfo(name = "reply_to_id") val replyToId: String?,
    @ColumnInfo(name = "sent_at") val sentAtMillis: Long,
    @ColumnInfo(name = "server_seq") val serverSeq: Long?,
    @ColumnInfo(name = "server_ts") val serverTsMillis: Long?,
    @ColumnInfo(name = "received_at") val receivedAtMillis: Long?,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
)

@Entity(
    tableName = "outbox",
    indices = [Index(value = ["state", "next_attempt_at"], name = "idx_outbox_due")],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["message_id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class OutboxEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "envelope_blob") val envelopeBlob: ByteArray?,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAtMillis: Long,
    val state: Int = 0,
    @ColumnInfo(name = "expires_at") val expiresAtMillis: Long,
    @ColumnInfo(name = "last_error") val lastErrorCode: Int?,
)

@Entity(tableName = "sync_cursor")
internal data class SyncCursorEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "last_confirmed_seq") val lastConfirmedSeq: Long,
)
