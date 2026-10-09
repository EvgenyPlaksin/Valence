package com.valence.server.domain.usecase

import com.valence.server.domain.model.Envelope
import com.valence.server.domain.repository.SessionRepository

sealed interface RouteResult {
    data class Success(val deliveredImmediately: Boolean) : RouteResult
    data class UserOffline(val queuedForLater: Boolean) : RouteResult
    data class ValidationError(val reason: String) : RouteResult
}

class RouteEnvelopeUseCase(
    private val sessionRepository: SessionRepository
) {
    suspend fun execute(envelope: Envelope): RouteResult {
        if (envelope.id.isBlank()) {
            return RouteResult.ValidationError("Envelope ID cannot be blank")
        }
        if (envelope.senderId.isBlank()) {
            return RouteResult.ValidationError("Sender ID cannot be blank")
        }
        if (envelope.recipientId.isBlank()) {
            return RouteResult.ValidationError("Recipient ID cannot be blank")
        }
        if (envelope.payload.isBlank()) {
            return RouteResult.ValidationError("Payload cannot be blank")
        }

        if (!sessionRepository.isUserOnline(envelope.recipientId)) {
            return RouteResult.UserOffline(queuedForLater = false)
        }

        val sent = sessionRepository.sendEnvelope(envelope.recipientId, envelope)
        return if (sent) {
            RouteResult.Success(deliveredImmediately = true)
        } else {
            RouteResult.UserOffline(queuedForLater = false)
        }
    }
}
