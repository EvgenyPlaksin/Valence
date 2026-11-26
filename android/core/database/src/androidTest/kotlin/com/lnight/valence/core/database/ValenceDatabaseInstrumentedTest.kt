package com.lnight.valence.core.database

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lnight.valence.core.model.Contact
import com.lnight.valence.core.model.ContactId
import com.lnight.valence.core.model.Conversation
import com.lnight.valence.core.model.ConversationId
import com.lnight.valence.core.model.MailboxId
import com.lnight.valence.core.model.Message
import com.lnight.valence.core.model.MessageDirection
import com.lnight.valence.core.model.MessageId
import com.lnight.valence.core.model.MessageKind
import com.lnight.valence.core.model.MessageStatus
import com.lnight.valence.core.model.Outbox
import com.lnight.valence.core.model.OutboxState
import com.lnight.valence.core.model.repository.MessageEnqueueResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ValenceDatabaseInstrumentedTest {
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            RoomValenceDatabase::class.java,
            listOf(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class ConstantPassphrase(
        private val key: ByteArray,
    ) : DatabasePassphraseProvider {
        override fun takePassphrase(): ByteArray = key.copyOf()
    }

    @Test
    fun schemaV1CanBeCreatedAndValidated() {
        val db: SupportSQLiteDatabase = helper.createDatabase("migration-test.db", 1)
        db.close()
    }

    @Test
    fun encryptedDatabaseRejectsWrongKey() {
        val dbFile = File(context.applicationInfo.dataDir, "databases/sqlcipher-test.db")
        if (dbFile.exists()) dbFile.delete()

        val validKey = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val wrongKey = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9)

        val factoryValid =
            ValenceDatabaseFactory.create(
                context = context,
                passphraseProvider = ConstantPassphrase(validKey),
                databaseName = "sqlcipher-test.db",
            )
        runBlocking {
            factoryValid.keyStore.putDeliveryKey(
                DeliveryKeyStorage(ImmutableBytes(byteArrayOf(42)), 100L),
            )
        }
        factoryValid.close()

        assertThrows(SQLiteException::class.java) {
            val factoryWrong =
                ValenceDatabaseFactory.create(
                    context = context,
                    passphraseProvider = ConstantPassphrase(wrongKey),
                    databaseName = "sqlcipher-test.db",
                )
            runBlocking {
                factoryWrong.keyStore.getDeliveryKey()
            }
            factoryWrong.close()
        }

        if (dbFile.exists()) dbFile.delete()
    }

    @Test
    fun atomicInboundCommitDeletesConsumedPreKeysRetainingLastResort() =
        runBlocking {
            val db = ValenceDatabaseFactory.createInMemory(context, ConstantPassphrase(byteArrayOf(1, 2, 3)))
            try {
                setupContactAndConversation(db)
                setupInboundPreKeys(db)

                val session =
                    SignalSessionStorage(
                        contactId = ContactId("c1"),
                        recordBlob = ImmutableBytes(byteArrayOf(5, 5, 5)),
                        recordIv = ImmutableBytes(byteArrayOf(6, 6, 6)),
                        recordVersion = 1,
                        updatedAtMillis = 2000L,
                    )
                val message =
                    Message(
                        id = MessageId("msg-1"),
                        conversationId = ConversationId("conv1"),
                        direction = MessageDirection.Inbound,
                        status = MessageStatus.Delivered,
                        kind = MessageKind.Text,
                        body = "Hello",
                        replyToId = null,
                        sentAtMillis = 2000L,
                        serverSeq = 1L,
                        serverTsMillis = 2005L,
                        receivedAtMillis = 2010L,
                        createdAtMillis = 2010L,
                    )
                val commit =
                    InboundMessageCommit(
                        message = message,
                        session = session,
                        consumedOneTimePreKeyId = 10,
                        consumedOneTimeKyberPreKeyId = null,
                    )

                val result = db.messageStore.commitInbound(commit)
                assertEquals(InboundCommitResult.Stored, result)
                assertNull(db.keyStore.getOneTimePreKey(10))
                assertNotNull(db.keyStore.getKyberPreKey(99))

                val storedSession = db.sessionStore.getSession(ContactId("c1"))
                assertNotNull(storedSession)
                assertArrayEquals(byteArrayOf(5, 5, 5), storedSession?.recordBlob?.copyBytes())

                val conv = db.conversationStore.observeConversation(ConversationId("conv1")).first()
                assertEquals(MessageId("msg-1"), conv?.lastMessageId)
                assertEquals(1, conv?.unreadCount)

                val duplicateResult = db.messageStore.commitInbound(commit)
                assertEquals(InboundCommitResult.Duplicate, duplicateResult)
            } finally {
                db.close()
            }
        }

    private suspend fun setupContactAndConversation(db: ValenceDatabase) {
        val contact =
            Contact(
                id = ContactId("c1"),
                mailboxId = MailboxId("mb1"),
                displayName = "Alice",
                createdAtMillis = 1000L,
            )
        val contactKeys =
            ContactKeyStorage(
                contactId = "c1",
                identityKey = ImmutableBytes(byteArrayOf(1)),
                deliveryKey = ImmutableBytes(byteArrayOf(2)),
                safetyNumberHash = null,
            )
        db.contactStore.putContact(contact, contactKeys)

        val conversation =
            Conversation(
                id = ConversationId("conv1"),
                contactId = ContactId("c1"),
                lastMessageId = null,
                lastMessageAtMillis = null,
                unreadCount = 0,
                pinned = false,
                mutedUntilMillis = null,
            )
        db.conversationStore.putConversation(conversation)
    }

    private suspend fun setupInboundPreKeys(db: ValenceDatabase) {
        db.keyStore.putOneTimePreKeys(
            listOf(
                OneTimePreKeyStorage(
                    preKeyId = 10,
                    publicKey = ImmutableBytes(byteArrayOf(10)),
                    wrappedPrivateKey = ImmutableBytes(byteArrayOf(11)),
                    wrapIv = ImmutableBytes(byteArrayOf(12)),
                    uploaded = true,
                ),
            ),
        )
        db.keyStore.replaceLastResortKyberPreKey(
            KyberPreKeyStorage(
                preKeyId = 99,
                publicKey = ImmutableBytes(byteArrayOf(99)),
                wrappedPrivateKey = ImmutableBytes(byteArrayOf(98)),
                wrapIv = ImmutableBytes(byteArrayOf(97)),
                signature = ImmutableBytes(byteArrayOf(96)),
                role = KyberPreKeyRole.LastResort,
                uploaded = true,
                createdAtMillis = 1000L,
                retiredAtMillis = null,
            ),
            retiredAtMillis = 1000L,
        )
    }

    @Test
    fun atomicOutboundLifecycle() =
        runBlocking {
            val db = ValenceDatabaseFactory.createInMemory(context, ConstantPassphrase(byteArrayOf(1, 2, 3)))
            try {
                setupContactAndConversation(db)

                val message =
                    Message(
                        id = MessageId("msg-out-1"),
                        conversationId = ConversationId("conv1"),
                        direction = MessageDirection.Outbound,
                        status = MessageStatus.Pending,
                        kind = MessageKind.Text,
                        body = "Outbound test",
                        replyToId = null,
                        sentAtMillis = 1000L,
                        serverSeq = null,
                        serverTsMillis = null,
                        receivedAtMillis = null,
                        createdAtMillis = 1000L,
                    )

                val enqueueResult = db.messageStore.enqueue(message)
                assertEquals(MessageEnqueueResult.Enqueued, enqueueResult)

                val session =
                    SignalSessionStorage(
                        contactId = ContactId("c1"),
                        recordBlob = ImmutableBytes(byteArrayOf(1, 1)),
                        recordIv = ImmutableBytes(byteArrayOf(2, 2)),
                        recordVersion = 1,
                        updatedAtMillis = 1050L,
                    )
                val envelope = ImmutableBytes(byteArrayOf(9, 9, 9))
                db.messageStore.sealOutbound(message.id, session, envelope)

                val due = db.outboxStore.dueItems(2000L)
                assertEquals(1, due.size)
                assertArrayEquals(byteArrayOf(9, 9, 9), due[0].envelope.copyBytes())

                val pausedCount = db.outboxStore.pauseForContact(ContactId("c1"))
                assertEquals(1, pausedCount)
                assertEquals(0, db.outboxStore.dueItems(2000L).size)

                val resumedCount = db.outboxStore.resumeForContact(ContactId("c1"), 2500L)
                assertEquals(1, resumedCount)
                assertEquals(1, db.outboxStore.dueItems(3000L).size)

                db.messageStore.markSent(message.id, 2600L)
                assertEquals(0, db.outboxStore.dueItems(3000L).size)
                val updatedMsg = db.messageStore.getMessage(message.id)
                assertEquals(MessageStatus.Sent, updatedMsg?.status)
                assertEquals(2600L, updatedMsg?.serverTsMillis)
            } finally {
                db.close()
            }
        }

    @Test
    fun failExpiredOutboxItemsMarksMessageFailedAndDeletesOutboxRow() =
        runBlocking {
            val db = ValenceDatabaseFactory.createInMemory(context, ConstantPassphrase(byteArrayOf(1, 2, 3)))
            try {
                val contact =
                    Contact(
                        id = ContactId("c1"),
                        mailboxId = MailboxId("mb1"),
                        displayName = "Bob",
                        createdAtMillis = 1000L,
                    )
                db.contactStore.putContact(
                    contact,
                    ContactKeyStorage("c1", ImmutableBytes(byteArrayOf(1)), ImmutableBytes(byteArrayOf(2)), null),
                )
                db.conversationStore.putConversation(
                    Conversation(ConversationId("conv1"), ContactId("c1"), null, null, 0, false, null),
                )

                val message =
                    Message(
                        id = MessageId("msg-expired"),
                        conversationId = ConversationId("conv1"),
                        direction = MessageDirection.Outbound,
                        status = MessageStatus.Pending,
                        kind = MessageKind.Text,
                        body = "Expiring",
                        replyToId = null,
                        sentAtMillis = 1000L,
                        serverSeq = null,
                        serverTsMillis = null,
                        receivedAtMillis = null,
                        createdAtMillis = 1000L,
                    )
                db.messageStore.enqueue(message)

                val futureMillis = 1000L + 31L * 24 * 60 * 60 * 1000L
                val failedCount = db.outboxStore.failExpired(futureMillis)
                assertEquals(1, failedCount)

                val failedMsg = db.messageStore.getMessage(message.id)
                assertEquals(MessageStatus.Failed, failedMsg?.status)
                assertEquals(0, db.outboxStore.dueItems(futureMillis).size)
            } finally {
                db.close()
            }
        }

    @Test
    fun cascadeDeletesConversationAndMessagesOnContactDelete() =
        runBlocking {
            val db = ValenceDatabaseFactory.createInMemory(context, ConstantPassphrase(byteArrayOf(1, 2, 3)))
            try {
                val contact =
                    Contact(
                        id = ContactId("c1"),
                        mailboxId = MailboxId("mb1"),
                        displayName = "Alice",
                        createdAtMillis = 1000L,
                    )
                db.contactStore.putContact(
                    contact,
                    ContactKeyStorage("c1", ImmutableBytes(byteArrayOf(1)), ImmutableBytes(byteArrayOf(2)), null),
                )
                db.conversationStore.putConversation(
                    Conversation(ConversationId("conv1"), ContactId("c1"), null, null, 0, false, null),
                )
                db.messageStore.enqueue(
                    Message(
                        id = MessageId("m1"),
                        conversationId = ConversationId("conv1"),
                        direction = MessageDirection.Outbound,
                        status = MessageStatus.Pending,
                        kind = MessageKind.Text,
                        body = "hi",
                        replyToId = null,
                        sentAtMillis = 1000L,
                        serverSeq = null,
                        serverTsMillis = null,
                        receivedAtMillis = null,
                        createdAtMillis = 1000L,
                    ),
                )

                val roomDb = (db as ValenceDatabaseImpl).roomDatabase
                val deleted = roomDb.contactDao().deleteContact("c1")
                assertEquals(1, deleted)

                assertNull(db.conversationStore.observeConversation(ConversationId("conv1")).first())
                assertNull(db.messageStore.getMessage(MessageId("m1")))
            } finally {
                db.close()
            }
        }
}
