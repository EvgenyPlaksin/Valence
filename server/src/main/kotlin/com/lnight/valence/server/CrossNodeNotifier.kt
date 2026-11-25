package com.lnight.valence.server

import com.lnight.valence.server.domain.MessageNotifier
import com.lnight.valence.server.domain.SocketGenerationNotifier
import java.io.Closeable

interface CrossNodeNotifier :
    MessageNotifier,
    SocketGenerationNotifier,
    Closeable {
    fun start()

    fun gauges(): Map<String, Long>
}

class LocalCrossNodeNotifier : CrossNodeNotifier {
    override fun messageStored(mailboxId: String) = Unit

    override fun generationAdvanced(
        mailboxId: String,
        generation: Long,
    ) = Unit

    override fun start() = Unit

    override fun gauges(): Map<String, Long> = emptyMap()

    override fun close() = Unit
}
