package com.lnight.valence.protocol

import com.lnight.valence.protocol.frame.ClientFrame
import com.lnight.valence.protocol.frame.ServerFrame
import kotlinx.serialization.json.Json

object ProtocolJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            coerceInputValues = true
        }

    fun encodeServerFrame(frame: ServerFrame): String = json.encodeToString(ServerFrame.serializer(), frame)

    fun decodeServerFrame(text: String): ServerFrame = json.decodeFromString(ServerFrame.serializer(), text)

    fun encodeClientFrame(frame: ClientFrame): String = json.encodeToString(ClientFrame.serializer(), frame)

    fun decodeClientFrame(text: String): ClientFrame = json.decodeFromString(ClientFrame.serializer(), text)
}
