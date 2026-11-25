package com.lnight.valence.server.routes

import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.protocol.ProtocolVersion
import com.lnight.valence.protocol.frame.DeliveryConfirmFrame
import com.lnight.valence.protocol.frame.ErrorCode
import com.lnight.valence.protocol.frame.ErrorFrame
import com.lnight.valence.protocol.frame.SyncResumeFrame
import com.lnight.valence.server.CrossNodeNotifier
import com.lnight.valence.server.domain.ConfirmResult
import com.lnight.valence.server.domain.InboxStore
import com.lnight.valence.server.domain.MailboxSubscriptions
import com.lnight.valence.server.domain.RateLimitDecision
import com.lnight.valence.server.domain.ReceiveService
import com.lnight.valence.server.domain.ReceiveSession
import com.lnight.valence.server.domain.Subscription
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ThreadLocalRandom

private const val MAX_CLIENT_FRAME_BYTES = 4_096
private const val SUPERSEDED_CLOSE_CODE: Short = 4000

private class Connection(
    val session: ReceiveSession,
    val subscription: Subscription,
    val mapper: MessageDeliverFrameMapper,
    val metrics: ServerMetrics,
)

fun Route.socketRoutes(
    receive: ReceiveService,
    subscriptions: MailboxSubscriptions,
    socketState: InboxStore,
    generationNotifier: CrossNodeNotifier,
    mapper: MessageDeliverFrameMapper,
    metrics: ServerMetrics,
) {
    authenticate(ACCESS_TOKEN_AUTH) {
        webSocket(ProtocolRoutes.SOCKET) {
            val draining = call.application.attributes[SERVER_DRAINING]
            if (synchronized(draining) { draining.get() }) {
                close(CloseReason(CloseReason.Codes.GOING_AWAY, "server shutting down"))
                return@webSocket
            }
            val principal =
                checkNotNull(call.principal<MailboxPrincipal>()) { "Authenticated route without a principal" }
            val generation = socketState.advanceSocketGeneration(principal.mailboxId)
            if (generation == null) {
                close(CloseReason(CloseReason.Codes.GOING_AWAY, "mailbox unavailable"))
                return@webSocket
            }
            val subscription = subscriptions.subscribe(principal.mailboxId, generation)
            generationNotifier.generationAdvanced(principal.mailboxId, generation)
            metrics.increment(MetricNames.SOCKETS_OPENED)
            try {
                serve(Connection(receive.open(principal.mailboxId, generation), subscription, mapper, metrics))
            } finally {
                subscriptions.unsubscribe(principal.mailboxId, subscription)
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.serve(connection: Connection) =
    coroutineScope {
        val pump = launch { pump(connection) }
        val watchers =
            listOf(
                launch {
                    connection.subscription.superseded.await()
                    close(CloseReason(SUPERSEDED_CLOSE_CODE, "superseded"))
                },
                launch {
                    connection.subscription.shuttingDown.await()
                    close(CloseReason(CloseReason.Codes.GOING_AWAY, "server shutting down"))
                },
            )
        readFrames(connection)
        pump.cancel()
        watchers.forEach { it.cancel() }
    }

private suspend fun DefaultWebSocketServerSession.pump(connection: Connection) {
    while (true) {
        waitForWakeOrPoll(connection)
        val status = connection.session.checkForUpdates()
        if (status != null) {
            if (status.generation != connection.subscription.generation) {
                close(CloseReason(SUPERSEDED_CLOSE_CODE, "superseded"))
                return
            }
            if (status.hasMessages) deliverMessages(connection)
        }
    }
}

private suspend fun DefaultWebSocketServerSession.waitForWakeOrPoll(connection: Connection) {
    withTimeoutOrNull(nextPollDelayMillis()) {
        connection.subscription.wake.receive()
    }
}

private suspend fun DefaultWebSocketServerSession.deliverMessages(connection: Connection) {
    do {
        val batch = connection.session.nextBatch()
        connection.metrics.increment(MetricNames.MESSAGES_DELIVERED, by = batch.size.toLong())
        batch.forEach { message ->
            send(Frame.Text(ProtocolJson.encodeServerFrame(connection.mapper.toFrame(message))))
        }
    } while (batch.isNotEmpty())
}

private fun nextPollDelayMillis(): Long =
    ThreadLocalRandom.current().nextLong(POLL_MIN_DELAY_MILLIS, POLL_MAX_DELAY_MILLIS)

private suspend fun DefaultWebSocketServerSession.readFrames(connection: Connection) {
    for (frame in incoming) {
        if ((frame is Frame.Text || frame is Frame.Binary) && !process(frame, connection)) return
    }
}

private suspend fun DefaultWebSocketServerSession.process(
    frame: Frame,
    connection: Connection,
): Boolean {
    val decision = connection.session.allowFrame()
    return when {
        decision is RateLimitDecision.Limited -> {
            val error = ErrorFrame(code = ErrorCode.RATE_LIMITED, retryAfterMs = decision.retryAfterMillis)
            send(Frame.Text(ProtocolJson.encodeServerFrame(error)))
            true
        }

        frame !is Frame.Text -> {
            rejectFrame(connection)
            true
        }

        frame.data.size > MAX_CLIENT_FRAME_BYTES -> {
            close(CloseReason(CloseReason.Codes.TOO_BIG, "frame too large"))
            false
        }

        else -> {
            handle(frame.readText(), connection)
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handle(
    text: String,
    connection: Connection,
): Boolean {
    val frame = Parsing.orNull { ProtocolJson.decodeClientFrame(text) }
    val confirmation =
        (frame as? DeliveryConfirmFrame)
            ?.takeIf { ProtocolVersion.isSupported(it.v) }
            ?.let { connection.session.confirm(it.upToServerSeq) }
    val stale = confirmation == ConfirmResult.STALE
    if (stale) close(CloseReason(SUPERSEDED_CLOSE_CODE, "superseded"))
    val accepted =
        when (frame) {
            is SyncResumeFrame -> resume(frame, connection)
            is DeliveryConfirmFrame -> confirmation == ConfirmResult.CONFIRMED
            null -> false
        }
    if (!accepted && !stale) {
        rejectFrame(connection)
    }
    return !stale
}

private suspend fun resume(
    frame: SyncResumeFrame,
    connection: Connection,
): Boolean {
    val accepted = ProtocolVersion.isSupported(frame.v) && connection.session.resume(frame.afterServerSeq)
    if (accepted) connection.subscription.wake.trySend(Unit)
    return accepted
}

private suspend fun DefaultWebSocketServerSession.rejectFrame(connection: Connection) {
    connection.metrics.increment(MetricNames.INVALID_FRAMES)
    send(Frame.Text(ProtocolJson.encodeServerFrame(ErrorFrame(code = ErrorCode.INVALID_FRAME))))
}

private const val POLL_MIN_DELAY_MILLIS = 15_000L
private const val POLL_MAX_DELAY_MILLIS = 45_001L
