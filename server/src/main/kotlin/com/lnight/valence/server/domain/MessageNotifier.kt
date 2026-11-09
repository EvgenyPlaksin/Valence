package com.lnight.valence.server.domain

fun interface MessageNotifier {
    fun messageStored(mailboxId: String)
}
