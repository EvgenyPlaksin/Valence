package com.lnight.valence.server.domain

object MessageLimits {
    const val ENVELOPE_BYTES = 65_536
    const val MIN_TTL_SECONDS = 1L
    const val MAX_TTL_SECONDS = 2_592_000L
    const val MAX_QUEUED_PER_MAILBOX = 1_000
}
