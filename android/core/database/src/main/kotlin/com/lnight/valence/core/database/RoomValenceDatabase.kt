package com.lnight.valence.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        IdentityKeyEntity::class,
        SignedPreKeyEntity::class,
        KyberPreKeyEntity::class,
        OneTimePreKeyEntity::class,
        SignalSessionEntity::class,
        DeliveryKeyEntity::class,
        SenderCertificateEntity::class,
        ContactEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        OutboxEntity::class,
        SyncCursorEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(
    IdentifierTypeConverters::class,
    EnumTypeConverters::class,
)
internal abstract class RoomValenceDatabase : RoomDatabase() {
    abstract fun keyDao(): KeyDao

    abstract fun sessionDao(): SessionDao

    abstract fun contactDao(): ContactDao

    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    abstract fun outboxDao(): OutboxDao

    abstract fun syncCursorDao(): SyncCursorDao
}
