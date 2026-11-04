package com.lnight.valence.server.routes

import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.KSerializer

internal object Parsing {
    inline fun <T> orNull(block: () -> T): T? =
        runCatching(block)
            .onFailure { if (it !is IllegalArgumentException) throw it }
            .getOrNull()

    fun <T : Any> decode(
        text: String,
        serializer: KSerializer<T>,
        versionOf: (T) -> Int,
    ): T? =
        orNull { ProtocolJson.json.decodeFromString(serializer, text) }
            ?.takeIf { ProtocolVersion.isSupported(versionOf(it)) }
}
