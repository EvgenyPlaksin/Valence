package com.lnight.valence.server.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

class Subscription {
    val wake = Channel<Unit>(Channel.CONFLATED)
    val superseded = CompletableDeferred<Unit>()

    internal fun supersede() {
        superseded.complete(Unit)
    }
}
