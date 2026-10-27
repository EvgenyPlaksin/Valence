package com.lnight.valence.server.routes

internal object Parsing {
    inline fun <T> orNull(block: () -> T): T? =
        runCatching(block)
            .onFailure { if (it !is IllegalArgumentException) throw it }
            .getOrNull()
}
