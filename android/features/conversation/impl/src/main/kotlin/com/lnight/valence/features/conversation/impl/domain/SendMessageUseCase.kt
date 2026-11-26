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
import com.lnight.valence.core.model.MessageKind
import com.lnight.valence.core.model.MessageStatus
import com.lnight.valence.core.model.repository.MessageEnqueueResult
import com.lnight.valence.core.model.repository.MessageRepository
import javax.inject.Inject

class SendMessageUseCase
    @Inject
    constructor(
        private val messageRepository: MessageRepository,
        private val idGenerator: IdGenerator,
        private val clock: Clock,
    ) {
        suspend operator fun invoke(
            conversationId: ConversationId,
            text: String,
        ): AppResult<Message> {
            if (text.isBlank()) {
                return AppResult.Failure(AppError.Validation(ValidationFailure.BlankMessageText))
            }

            val nowMillis = clock.nowMillis()
            val message =
                Message(
                    id = idGenerator.newMessageId(),
                    conversationId = conversationId,
                    direction = MessageDirection.Outbound,
                    status = MessageStatus.Pending,
                    kind = MessageKind.Text,
                    body = text,
                    replyToId = null,
                    sentAtMillis = nowMillis,
                    serverSeq = null,
                    serverTsMillis = null,
                    receivedAtMillis = null,
                    createdAtMillis = nowMillis,
                )

            return when (messageRepository.enqueue(message)) {
                MessageEnqueueResult.Enqueued -> {
                    AppResult.Success(message)
                }

                is MessageEnqueueResult.Failed -> {
                    AppResult.Failure(AppError.Persistence(PersistenceFailure.WriteFailed))
                }
            }
        }
    }
