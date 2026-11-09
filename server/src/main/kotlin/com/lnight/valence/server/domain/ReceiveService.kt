package com.lnight.valence.server.domain

import java.time.Clock

class ReceiveService(
    private val inbox: InboxStore,
    private val clock: Clock,
) {
    fun open(mailboxId: String): ReceiveSession = ReceiveSession(mailboxId, inbox, clock)
}
