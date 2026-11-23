package com.lnight.valence.server.domain

import java.time.Clock

class InboxPurger(
    private val inbox: InboxStore,
    private val clock: Clock,
) {
    suspend fun purge(): Int = inbox.purgeExpired(clock.millis())
}
