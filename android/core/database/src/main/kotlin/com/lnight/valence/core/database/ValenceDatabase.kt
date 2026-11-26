package com.lnight.valence.core.database

import androidx.room.withTransaction
import com.lnight.valence.core.model.Contact
import com.lnight.valence.core.model.ContactId
import com.lnight.valence.core.model.Conversation
import com.lnight.valence.core.model.ConversationId
import com.lnight.valence.core.model.Message
import com.lnight.valence.core.model.MessageDirection
import com.lnight.valence.core.model.MessageId
import com.lnight.valence.core.model.MessageStatus
import com.lnight.valence.core.model.Outbox
import com.lnight.valence.core.model.OutboxState
import com.lnight.valence.core.model.repository.MessageEnqueueFailure
import com.lnight.valence.core.model.repository.MessageEnqueueResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

interface ValenceDatabase {
    val keyStore: LocalKeyStore
    val sessionStore: LocalSessionStore
    val contactStore: LocalContactStore
    val conversationStore: LocalConversationStore
    val messageStore: LocalMessageStore
    val outboxStore: LocalOutboxStore

    fun close()
}

internal class ValenceDatabaseImpl(
    val roomDatabase: RoomValenceDatabase,
) : ValenceDatabase {
    private val contactMapper = ContactEntityMapper()
    private val conversationMapper = ConversationEntityMapper()
    private val messageMapper = MessageEntityMapper()
    private val outboxMapper = OutboxEntityMapper()

    override val keyStore: LocalKeyStore = KeyStoreImpl(roomDatabase.keyDao())
    override val sessionStore: LocalSessionStore = SessionStoreImpl(roomDatabase.sessionDao())
    override val contactStore: LocalContactStore = ContactStoreImpl(roomDatabase.contactDao(), contactMapper)
    override val conversationStore: LocalConversationStore =
        ConversationStoreImpl(roomDatabase.conversationDao(), conversationMapper)
    override val messageStore: LocalMessageStore =
        MessageStoreImpl(roomDatabase, messageMapper, conversationMapper)
    override val outboxStore: LocalOutboxStore =
        OutboxStoreImpl(roomDatabase.outboxDao(), outboxMapper)

    override fun close() {
        roomDatabase.close()
    }
}

internal class KeyStoreImpl(
    private val dao: KeyDao,
) : LocalKeyStore {
    override suspend fun putIdentityKey(key: IdentityKeyStorage) {
        dao.insertIdentityKey(
            IdentityKeyEntity(
                id = 1,
                publicKey = key.publicKey.copyBytes(),
                wrappedPrivateKey = key.wrappedPrivateKey.copyBytes(),
                wrapIv = key.wrapIv.copyBytes(),
                keyStoreAlias = key.keyStoreAlias,
                createdAtMillis = key.createdAtMillis,
            ),
        )
    }

    override suspend fun putSignedPreKey(preKey: SignedPreKeyStorage) {
        dao.insertSignedPreKey(
            SignedPreKeyEntity(
                preKeyId = preKey.preKeyId,
                publicKey = preKey.publicKey.copyBytes(),
                wrappedPrivateKey = preKey.wrappedPrivateKey.copyBytes(),
                wrapIv = preKey.wrapIv.copyBytes(),
                signature = preKey.signature.copyBytes(),
                createdAtMillis = preKey.createdAtMillis,
                retiredAtMillis = preKey.retiredAtMillis,
            ),
        )
    }

    override suspend fun replaceLastResortKyberPreKey(
        preKey: KyberPreKeyStorage,
        retiredAtMillis: Long,
    ) {
        val entity =
            KyberPreKeyEntity(
                preKeyId = preKey.preKeyId,
                publicKey = preKey.publicKey.copyBytes(),
                wrappedPrivateKey = preKey.wrappedPrivateKey.copyBytes(),
                wrapIv = preKey.wrapIv.copyBytes(),
                signature = preKey.signature.copyBytes(),
                role = KyberPreKeyRole.LastResort.ordinal,
                uploaded = if (preKey.uploaded) 1 else 0,
                createdAtMillis = preKey.createdAtMillis,
                retiredAtMillis = preKey.retiredAtMillis,
            )
        dao.replaceLastResortKyberPreKey(entity, retiredAtMillis)
    }

    override suspend fun putOneTimePreKeys(preKeys: List<OneTimePreKeyStorage>) {
        dao.insertOneTimePreKeys(
            preKeys.map { preKey ->
                OneTimePreKeyEntity(
                    preKeyId = preKey.preKeyId,
                    publicKey = preKey.publicKey.copyBytes(),
                    wrappedPrivateKey = preKey.wrappedPrivateKey.copyBytes(),
                    wrapIv = preKey.wrapIv.copyBytes(),
                    uploaded = if (preKey.uploaded) 1 else 0,
                )
            },
        )
    }

    override suspend fun getOneTimePreKey(preKeyId: Int): OneTimePreKeyStorage? {
        val entity = dao.getOneTimePreKey(preKeyId) ?: return null
        return OneTimePreKeyStorage(
            preKeyId = entity.preKeyId,
            publicKey = ImmutableBytes(entity.publicKey),
            wrappedPrivateKey = ImmutableBytes(entity.wrappedPrivateKey),
            wrapIv = ImmutableBytes(entity.wrapIv),
            uploaded = entity.uploaded != 0,
        )
    }

    override suspend fun getKyberPreKey(preKeyId: Int): KyberPreKeyStorage? {
        val entity = dao.getKyberPreKey(preKeyId) ?: return null
        return KyberPreKeyStorage(
            preKeyId = entity.preKeyId,
            publicKey = ImmutableBytes(entity.publicKey),
            wrappedPrivateKey = ImmutableBytes(entity.wrappedPrivateKey),
            wrapIv = ImmutableBytes(entity.wrapIv),
            signature = ImmutableBytes(entity.signature),
            role = KyberPreKeyRole.entries[entity.role],
            uploaded = entity.uploaded != 0,
            createdAtMillis = entity.createdAtMillis,
            retiredAtMillis = entity.retiredAtMillis,
        )
    }

    override suspend fun getDeliveryKey(): DeliveryKeyStorage? {
        val entity = dao.getDeliveryKey() ?: return null
        return DeliveryKeyStorage(
            keyBytes = ImmutableBytes(entity.keyBytes),
            createdAtMillis = entity.createdAtMillis,
        )
    }

    override suspend fun putDeliveryKey(key: DeliveryKeyStorage) {
        dao.insertDeliveryKey(
            DeliveryKeyEntity(
                id = 1,
                keyBytes = key.keyBytes.copyBytes(),
                createdAtMillis = key.createdAtMillis,
            ),
        )
    }

    override suspend fun getSenderCertificate(): SenderCertificateStorage? {
        val entity = dao.getSenderCertificate() ?: return null
        return SenderCertificateStorage(
            certificate = ImmutableBytes(entity.certificate),
            expiresAtMillis = entity.expiresAtMillis,
        )
    }

    override suspend fun putSenderCertificate(certificate: SenderCertificateStorage) {
        dao.insertSenderCertificate(
            SenderCertificateEntity(
                id = 1,
                certificate = certificate.certificate.copyBytes(),
                expiresAtMillis = certificate.expiresAtMillis,
            ),
        )
    }

    override suspend fun deleteOneTimePreKey(preKeyId: Int) {
        dao.deleteOneTimePreKey(preKeyId)
    }

    override suspend fun deleteOneTimeKyberPreKey(preKeyId: Int) {
        dao.deleteOneTimeKyberPreKey(preKeyId)
    }

    override suspend fun purgeRetiredKyberPreKeys(retiredBeforeMillis: Long): Int =
        dao.purgeRetiredKyberPreKeys(retiredBeforeMillis)
}

internal class SessionStoreImpl(
    private val dao: SessionDao,
) : LocalSessionStore {
    override suspend fun getSession(contactId: ContactId): SignalSessionStorage? {
        val entity = dao.getSession(contactId.value) ?: return null
        return SignalSessionStorage(
            contactId = ContactId(entity.contactId),
            recordBlob = ImmutableBytes(entity.recordBlob),
            recordIv = ImmutableBytes(entity.recordIv),
            recordVersion = entity.recordVersion,
            updatedAtMillis = entity.updatedAtMillis,
        )
    }

    override suspend fun putSession(session: SignalSessionStorage) {
        dao.insertSession(
            SignalSessionEntity(
                contactId = session.contactId.value,
                recordBlob = session.recordBlob.copyBytes(),
                recordIv = session.recordIv.copyBytes(),
                recordVersion = session.recordVersion,
                updatedAtMillis = session.updatedAtMillis,
            ),
        )
    }
}

internal class ContactStoreImpl(
    private val dao: ContactDao,
    private val mapper: ContactEntityMapper,
) : LocalContactStore {
    override fun observeContacts(): Flow<List<Contact>> =
        dao.observeContacts().map { list -> list.map { mapper.toDomain(it) } }

    override suspend fun getContact(contactId: ContactId): Contact? =
        dao.getContact(contactId.value)?.let { mapper.toDomain(it) }

    override suspend fun putContact(
        contact: Contact,
        keys: ContactKeyStorage,
    ) {
        dao.insertContact(mapper.toEntity(contact, keys))
    }
}

internal class ConversationStoreImpl(
    private val dao: ConversationDao,
    private val mapper: ConversationEntityMapper,
) : LocalConversationStore {
    override fun observeConversations(): Flow<List<Conversation>> =
        dao.observeConversations().map { list -> list.map { mapper.toDomain(it) } }

    override fun observeConversation(conversationId: ConversationId): Flow<Conversation?> =
        dao.observeConversation(conversationId.value).map { it?.let { mapper.toDomain(it) } }

    override suspend fun putConversation(conversation: Conversation) {
        dao.insertConversation(mapper.toEntity(conversation))
    }
}

internal class MessageStoreImpl(
    private val roomDatabase: RoomValenceDatabase,
    private val messageMapper: MessageEntityMapper,
    private val conversationMapper: ConversationEntityMapper,
) : LocalMessageStore {
    override suspend fun enqueue(message: Message): MessageEnqueueResult {
        require(message.direction == MessageDirection.Outbound) {
            "Only outbound messages can be enqueued"
        }
        val entity = messageMapper.toEntity(message)
        val expiresAtMillis = message.createdAtMillis + TimeUnit.DAYS.toMillis(30)
        val outboxEntity =
            OutboxEntity(
                messageId = message.id.value,
                envelopeBlob = null,
                attemptCount = 0,
                nextAttemptAtMillis = message.sentAtMillis,
                state = OutboxState.Ready.ordinal,
                expiresAtMillis = expiresAtMillis,
                lastErrorCode = null,
            )

        return roomDatabase.withTransaction {
            val insertedRowId = roomDatabase.messageDao().insertMessage(entity)
            if (insertedRowId == -1L) {
                MessageEnqueueResult.Failed(MessageEnqueueFailure.Persistence)
            } else {
                roomDatabase.outboxDao().insertOutbox(outboxEntity)
                roomDatabase.conversationDao().updateSummary(
                    conversationId = message.conversationId.value,
                    lastMessageId = message.id.value,
                    lastMessageAtMillis = message.sentAtMillis,
                    unreadDelta = 0,
                )
                MessageEnqueueResult.Enqueued
            }
        }
    }

    override fun observeMessages(conversationId: ConversationId): Flow<List<Message>> =
        roomDatabase.messageDao().observeMessages(conversationId.value).map { list ->
            list.map { messageMapper.toDomain(it) }
        }

    override suspend fun getMessage(messageId: MessageId): Message? =
        roomDatabase.messageDao().getMessage(messageId.value)?.let { messageMapper.toDomain(it) }

    override suspend fun commitInbound(commit: InboundMessageCommit): InboundCommitResult {
        require(commit.message.direction == MessageDirection.Inbound) {
            "commitInbound requires an inbound message"
        }

        return roomDatabase.withTransaction {
            val inserted = roomDatabase.messageDao().insertMessage(messageMapper.toEntity(commit.message))
            if (inserted == -1L) {
                InboundCommitResult.Duplicate
            } else {
                roomDatabase.sessionDao().insertSession(
                    SignalSessionEntity(
                        contactId = commit.session.contactId.value,
                        recordBlob = commit.session.recordBlob.copyBytes(),
                        recordIv = commit.session.recordIv.copyBytes(),
                        recordVersion = commit.session.recordVersion,
                        updatedAtMillis = commit.session.updatedAtMillis,
                    ),
                )

                commit.consumedOneTimePreKeyId?.let { id ->
                    roomDatabase.keyDao().deleteOneTimePreKey(id)
                }

                commit.consumedOneTimeKyberPreKeyId?.let { id ->
                    roomDatabase.keyDao().deleteOneTimeKyberPreKey(id)
                }

                roomDatabase.conversationDao().updateSummary(
                    conversationId = commit.message.conversationId.value,
                    lastMessageId = commit.message.id.value,
                    lastMessageAtMillis = commit.message.sentAtMillis,
                    unreadDelta = 1,
                )

                commit.message.serverSeq?.let { seq ->
                    roomDatabase.syncCursorDao().setLastConfirmedSeq(
                        SyncCursorEntity(id = 1, lastConfirmedSeq = seq),
                    )
                }

                InboundCommitResult.Stored
            }
        }
    }

    override suspend fun sealOutbound(
        messageId: MessageId,
        session: SignalSessionStorage,
        envelope: ImmutableBytes,
    ) {
        roomDatabase.withTransaction {
            roomDatabase.sessionDao().insertSession(
                SignalSessionEntity(
                    contactId = session.contactId.value,
                    recordBlob = session.recordBlob.copyBytes(),
                    recordIv = session.recordIv.copyBytes(),
                    recordVersion = session.recordVersion,
                    updatedAtMillis = session.updatedAtMillis,
                ),
            )
            roomDatabase.outboxDao().updateEnvelope(messageId.value, envelope.copyBytes())
        }
    }

    override suspend fun markSent(
        messageId: MessageId,
        serverTsMillis: Long,
    ) {
        roomDatabase.withTransaction {
            roomDatabase.messageDao().updateSentStatus(
                messageId = messageId.value,
                status = MessageStatus.Sent.ordinal,
                serverTsMillis = serverTsMillis,
            )
            roomDatabase.outboxDao().deleteOutbox(messageId.value)
        }
    }
}

internal class OutboxStoreImpl(
    private val dao: OutboxDao,
    private val mapper: OutboxEntityMapper,
) : LocalOutboxStore {
    override suspend fun dueItems(nowMillis: Long): List<StoredOutboxItem> =
        dao.getDueOutboxItems(nowMillis).mapNotNull { entity ->
            val blob = entity.envelopeBlob ?: return@mapNotNull null
            StoredOutboxItem(
                outbox = mapper.toDomain(entity),
                envelope = ImmutableBytes(blob),
            )
        }

    override suspend fun pauseForContact(contactId: ContactId): Int = dao.pauseForContact(contactId.value)

    override suspend fun resumeForContact(
        contactId: ContactId,
        nowMillis: Long,
    ): Int = dao.resumeForContact(contactId.value, nowMillis)

    override suspend fun failExpired(nowMillis: Long): Int =
        dao.failExpiredItems(nowMillis, MessageStatus.Failed.ordinal)
}
