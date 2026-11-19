package com.lnight.valence.server

import com.lnight.valence.server.domain.InboxPurger
import io.ktor.server.application.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

fun Application.schedulePurge(
    purger: InboxPurger,
    interval: Duration,
) {
    launch {
        while (isActive) {
            delay(interval)
            purger.purge()
        }
    }
}
