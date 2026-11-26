package com.lnight.valence.features.conversation.impl.domain

import com.lnight.valence.core.common.AppError
import com.lnight.valence.core.common.AppResult
import com.lnight.valence.core.common.Clock
import com.lnight.valence.core.common.IdGenerator
import com.lnight.valence.core.common.PersistenceFailure
import com.lnight.valence.core.common.ValidationFailure
import com.lnight.valence.core.model.ConversationId
import com.lnight.valence.core.model.Message
import com.lnight.valence.core.model.MessageDirection
import com.lnight.valence.core.model.MessageId
import com.lnight.valence.core.model.MessageKind
import com.lnight.valence.core.model.MessageStatus
import com.lnight.valence.core.model.repository.MessageEnqueueFailure
import com.lnight.valence.core.model.repository.MessageEnqueueResult
import com.lnight.valence.core.model.repository.MessageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class SendMessageUseCaseTest {
    @Test
    fun `blank text is rejected without enqueueing`() {
        val repository = FakeMessageRepository()
        val useCase = sendMessageUseCase(repository)

        val result =
            runSuspending {
                useCase(ConversationId("conversation-1"), " \t\n")
            }

        assertEquals(
            AppResult.Failure(AppError.Validation(ValidationFailure.BlankMessageText)),
            result,
        )
        assertTrue(repository.enqueuedMessages.isEmpty())
    }

    @Test
    fun `valid text is enqueued as a pending outbound text message`() {
        val repository = FakeMessageRepository()
        val useCase = sendMessageUseCase(repository)

        val result =
            runSuspending {
                useCase(ConversationId("conversation-1"), "Hello")
            }

        val expectedMessage =
            Message(
                id = MessageId("message-1"),
                conversationId = ConversationId("conversation-1"),
                direction = MessageDirection.Outbound,
                status = MessageStatus.Pending,
                kind = MessageKind.Text,
                body = "Hello",
                replyToId = null,
                sentAtMillis = 1_000L,
                serverSeq = null,
                serverTsMillis = null,
                receivedAtMillis = null,
                createdAtMillis = 1_000L,
            )
        assertEquals(AppResult.Success(expectedMessage), result)
        assertEquals(listOf(expectedMessage), repository.enqueuedMessages)
    }

    @Test
    fun `repository failure is propagated`() {
        val failure = AppError.Persistence(PersistenceFailure.WriteFailed)
        val repository =
            FakeMessageRepository(
                enqueueResult = MessageEnqueueResult.Failed(MessageEnqueueFailure.Persistence),
            )
        val useCase = sendMessageUseCase(repository)

        val result =
            runSuspending {
                useCase(ConversationId("conversation-1"), "Hello")
            }

        assertEquals(AppResult.Failure(failure), result)
        assertEquals(1, repository.enqueuedMessages.size)
    }

    private fun sendMessageUseCase(repository: FakeMessageRepository) =
        SendMessageUseCase(
            messageRepository = repository,
            idGenerator = FixedIdGenerator,
            clock = FixedClock,
        )

    private class FakeMessageRepository(
        private val enqueueResult: MessageEnqueueResult = MessageEnqueueResult.Enqueued,
    ) : MessageRepository {
        val enqueuedMessages = mutableListOf<Message>()

        override suspend fun enqueue(message: Message): MessageEnqueueResult {
            enqueuedMessages += message
            return enqueueResult
        }
    }

    private object FixedIdGenerator : IdGenerator {
        override fun newMessageId() = MessageId("message-1")
    }

    private object FixedClock : Clock {
        override fun nowMillis() = 1_000L
    }

    private fun <T> runSuspending(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            },
        )
        return checkNotNull(outcome).getOrThrow()
    }
}
