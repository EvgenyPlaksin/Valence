package com.lnight.valence.server.routes

import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.protocol.ProtocolVersion
import com.lnight.valence.protocol.frame.DeliveryConfirmFrame
import com.lnight.valence.protocol.frame.ErrorCode
import com.lnight.valence.protocol.frame.ErrorFrame
import com.lnight.valence.protocol.frame.SyncResumeFrame
import com.lnight.valence.server.domain.MailboxSubscriptions
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
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val MAX_CLIENT_FRAME_BYTES = 4_096
private const val SUPERSEDED_CLOSE_CODE: Short = 4000

fun Route.socketRoutes(
    receive: ReceiveService,
    subscriptions: MailboxSubscriptions,
    mapper: MessageDeliverFrameMapper,
) {
    authenticate(ACCESS_TOKEN_AUTH) {
        webSocket(ProtocolRoutes.SOCKET) {
            val principal =
                checkNotNull(call.principal<MailboxPrincipal>()) { "Authenticated route without a principal" }
            val session = receive.open(principal.mailboxId)
            val subscription = subscriptions.subscribe(principal.mailboxId)
            try {
                serve(session, subscription, mapper)
            } finally {
                subscriptions.unsubscribe(principal.mailboxId, subscription)
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.serve(
    session: ReceiveSession,
    subscription: Subscription,
    mapper: MessageDeliverFrameMapper,
) = coroutineScope {
    val pump = launch { pump(session, subscription, mapper) }
    val watcher =
        launch {
            subscription.superseded.await()
            close(CloseReason(SUPERSEDED_CLOSE_CODE, "superseded"))
        }
    readFrames(session, subscription)
    pump.cancel()
    watcher.cancel()
}

private suspend fun DefaultWebSocketServerSession.pump(
    session: ReceiveSession,
    subscription: Subscription,
    mapper: MessageDeliverFrameMapper,
) {
    subscription.wake.consumeEach { _ ->
        do {
            val batch = session.nextBatch()
            batch.forEach { message -> send(Frame.Text(ProtocolJson.encodeServerFrame(mapper.toFrame(message)))) }
        } while (batch.isNotEmpty())
    }
}

private suspend fun DefaultWebSocketServerSession.readFrames(
    session: ReceiveSession,
    subscription: Subscription,
) {
    for (frame in incoming) {
        when {
            frame is Frame.Binary -> {
                rejectFrame()
            }

            frame !is Frame.Text -> {
                Unit
            }

            frame.data.size > MAX_CLIENT_FRAME_BYTES -> {
                close(CloseReason(CloseReason.Codes.TOO_BIG, "frame too large"))
                return
            }

            else -> {
                handle(frame.readText(), session, subscription)
            }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handle(
    text: String,
    session: ReceiveSession,
    subscription: Subscription,
) {
    val accepted =
        when (val frame = Parsing.orNull { ProtocolJson.decodeClientFrame(text) }) {
            is SyncResumeFrame -> resume(frame, session, subscription)
            is DeliveryConfirmFrame -> ProtocolVersion.isSupported(frame.v) && session.confirm(frame.upToServerSeq)
            null -> false
        }
    if (!accepted) rejectFrame()
}

private fun resume(
    frame: SyncResumeFrame,
    session: ReceiveSession,
    subscription: Subscription,
): Boolean {
    val accepted = ProtocolVersion.isSupported(frame.v) && session.resume(frame.afterServerSeq)
    if (accepted) subscription.wake.trySend(Unit)
    return accepted
}

private suspend fun DefaultWebSocketServerSession.rejectFrame() {
    send(Frame.Text(ProtocolJson.encodeServerFrame(ErrorFrame(code = ErrorCode.INVALID_FRAME))))
}
