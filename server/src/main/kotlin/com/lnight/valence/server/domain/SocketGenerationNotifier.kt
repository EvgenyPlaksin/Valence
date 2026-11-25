package com.lnight.valence.server.domain

fun interface SocketGenerationNotifier {
    fun generationAdvanced(
        mailboxId: String,
        generation: Long,
    )
}
