package com.lnight.valence.server.domain

import java.time.Clock

class ReceiveService(
    private val inbox: InboxStore,
    private val clock: Clock,
    private val framePolicy: RateLimitPolicy = RateLimitPolicies().socketFrames,
) {
    fun open(
        mailboxId: String,
        generation: Long,
    ): ReceiveSession = ReceiveSession(mailboxId, generation, inbox, clock, framePolicy)
}
