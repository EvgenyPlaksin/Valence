package com.lnight.valence.protocol

import com.lnight.valence.protocol.frame.ClientFrame
import com.lnight.valence.protocol.frame.ServerFrame
import com.lnight.valence.protocol.frame.ServerFrameSerializer
import com.lnight.valence.protocol.frame.UnknownServerFrameSerializer
import kotlinx.serialization.json.Json

object ProtocolJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            coerceInputValues = true
        }

    fun encodeServerFrame(frame: ServerFrame): String =
        if (frame is ServerFrame.Unknown) {
            json.encodeToString(UnknownServerFrameSerializer, frame)
        } else {
            json.encodeToString(ServerFrame.serializer(), frame)
        }

    fun decodeServerFrame(text: String): ServerFrame = json.decodeFromString(ServerFrameSerializer, text)

    fun encodeClientFrame(frame: ClientFrame): String = json.encodeToString(ClientFrame.serializer(), frame)

    fun decodeClientFrame(text: String): ClientFrame = json.decodeFromString(ClientFrame.serializer(), text)
}
