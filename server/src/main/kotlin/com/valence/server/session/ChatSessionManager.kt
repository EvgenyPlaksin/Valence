package com.valence.server.session

import com.valence.server.model.MessagePacket
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class ChatSessionManager(
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val logger = LoggerFactory.getLogger(ChatSessionManager::class.java)
    private val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()

    fun register(userId: String, session: DefaultWebSocketServerSession) {
        val previousSession = sessions.put(userId, session)
        if (previousSession != null && previousSession != session) {
            logger.info("Replaced existing session for user: {}", userId)
        } else {
            logger.info("Registered session for user: {}. Total active sessions: {}", userId, sessions.size)
        }
    }

    fun unregister(userId: String, session: DefaultWebSocketServerSession) {
        val removed = sessions.remove(userId, session)
        if (removed) {
            logger.info("Unregistered session for user: {}. Total active sessions: {}", userId, sessions.size)
        }
    }

    suspend fun routeMessage(packet: MessagePacket) {
        val recipientSession = sessions[packet.recipientId]
        if (recipientSession != null) {
            try {
                val serialized = json.encodeToString(packet)
                recipientSession.send(Frame.Text(serialized))
                logger.info("Successfully delivered message from {} to {}", packet.senderId, packet.recipientId)
            } catch (e: Exception) {
                logger.error("Failed to send message to recipient {}: {}", packet.recipientId, e.message, e)
            }
        } else {
            logger.info("Recipient {} is offline. Message from {} not delivered immediately", packet.recipientId, packet.senderId)
        }
    }

    fun isOnline(userId: String): Boolean = sessions.containsKey(userId)
}
