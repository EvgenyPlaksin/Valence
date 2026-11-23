package com.lnight.valence.server

import com.lnight.valence.server.domain.InboxPurger
import com.lnight.valence.server.routes.MetricNames
import com.lnight.valence.server.routes.ServerMetrics
import io.ktor.server.application.Application
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

fun Application.schedulePurge(
    purger: InboxPurger,
    interval: Duration,
    metrics: ServerMetrics,
) {
    launch {
        while (isActive) {
            delay(interval)
            val purged = purger.purge()
            if (purged > 0) metrics.increment(MetricNames.MESSAGES_PURGED, by = purged.toLong())
        }
    }
}
