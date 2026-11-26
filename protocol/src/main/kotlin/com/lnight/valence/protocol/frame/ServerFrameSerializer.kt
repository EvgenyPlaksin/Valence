package com.lnight.valence.protocol.frame

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ServerFrameSerializer : JsonContentPolymorphicSerializer<ServerFrame>(ServerFrame::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ServerFrame> =
        when (val type = extractType(element)) {
            "message.deliver" -> MessageDeliverFrame.serializer()
            "error" -> ErrorFrame.serializer()
            else -> UnknownServerFrameSerializer
        }

    private fun extractType(element: JsonElement): String {
        val jsonObject = element as? JsonObject
        val typePrimitive = jsonObject?.get("type") as? JsonPrimitive
        return when {
            jsonObject == null -> {
                throw SerializationException("Server frame must be a JSON object")
            }

            typePrimitive == null || !typePrimitive.isString -> {
                throw SerializationException("Server frame 'type' must be a string")
            }

            else -> {
                typePrimitive.content
            }
        }
    }
}

object UnknownServerFrameSerializer : KSerializer<ServerFrame.Unknown> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("ServerFrame.Unknown") {
            element<Int>("v", isOptional = true)
            element<String>("type", isOptional = true)
        }

    override fun deserialize(decoder: Decoder): ServerFrame.Unknown {
        val jsonDecoder =
            decoder as? JsonDecoder
                ?: throw SerializationException("Unknown frame requires JsonDecoder")
        val jsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val v = jsonObject["v"]?.jsonPrimitive?.intOrNull ?: ProtocolVersion.CURRENT
        val rawType = (jsonObject["type"] as? JsonPrimitive)?.content
        return ServerFrame.Unknown(v = v, rawType = rawType)
    }

    override fun serialize(
        encoder: Encoder,
        value: ServerFrame.Unknown,
    ) {
        val jsonEncoder =
            encoder as? JsonEncoder
                ?: throw SerializationException("Unknown frame requires JsonEncoder")
        val map =
            buildMap {
                put("type", JsonPrimitive(value.rawType ?: "unknown"))
                put("v", JsonPrimitive(value.v))
            }
        jsonEncoder.encodeJsonElement(JsonObject(map))
    }
}
