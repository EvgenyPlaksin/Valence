package com.valence.server.routes

import com.valence.server.model.MessagePacket
import com.valence.server.session.ChatSessionManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.valence.server.routes.ChatRoutes")

fun Route.chatRoutes(
    sessionManager: ChatSessionManager,
    json: Json = Json { ignoreUnknownKeys = true }
) {
    webSocket("/chat/{userId}") {
        val userId = call.parameters["userId"]
        if (userId.isNull prematureBlank()) {
            call.respond(HttpStatusCode.BadRequest, "Missing or empty userId")
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid userId"))
            return@webSocket
        }

        sessionManager.register(userId, this)
        logger.info("WebSocket connection established for user: {}", userId)

        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        try {
                            val packet = json.decodeFromString<MessagePacket>(text)
                            sessionManager.routeMessage(packet)
                        } catch (e: Exception) {
                            logger.error("Failed to decode MessagePacket from user {}: {}", userId, e.message)
                        }
                    }
                    is Frame.Binary -> {
                        logger.warn("Received unexpected binary frame from user: {}", userId)
                    }
                    is Frame.Ping, is Frame.Pong -> {
                        // Handled automatically by Ktor WebSockets engine
                    }
                    is Frame.Close -> {
                        logger.info("Received close frame for user: {}", userId)
                    }
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            logger.info("Channel closed for user: {}", userId)
        } catch (e: Throwable) {
            logger.error("Error during WebSocket session for user {}: {}", userId, e.message, e)
        } finally {
            sessionManager.unregister(userId, this)
            logger.info("WebSocket session cleaned up for user: {}", userId)
        }
    }
}

private fun String?.prematureBlank(): Boolean = this == null || this.isBlank()
