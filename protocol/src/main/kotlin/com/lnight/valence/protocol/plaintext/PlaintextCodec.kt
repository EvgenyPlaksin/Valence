package com.lnight.valence.protocol.plaintext

import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.SerializationException

object PlaintextCodec {
    private const val BUCKET_SIZE_BYTES = 256
    private const val BUCKET_ROUND_UP_OFFSET = 255
    private val validKinds = setOf("text", "attachment", "system")

    fun encode(payload: PlaintextPayload): ByteArray {
        validate(payload)
        val emptyPadPayload = payload.copy(padding = "")
        val emptyPadJson = ProtocolJson.json.encodeToString(PlaintextPayload.serializer(), emptyPadPayload)
        val emptyPadBytes = emptyPadJson.encodeToByteArray()
        val target =
            maxOf(
                BUCKET_SIZE_BYTES,
                ((emptyPadBytes.size + BUCKET_ROUND_UP_OFFSET) / BUCKET_SIZE_BYTES) * BUCKET_SIZE_BYTES,
            )
        val padChars = target - emptyPadBytes.size
        val paddedPayload = payload.copy(padding = "0".repeat(padChars))
        val bytes = ProtocolJson.json.encodeToString(PlaintextPayload.serializer(), paddedPayload).encodeToByteArray()
        check(bytes.size % BUCKET_SIZE_BYTES == 0) {
            "Encoded plaintext must be a multiple of $BUCKET_SIZE_BYTES bytes, got ${bytes.size}"
        }
        return bytes
    }

    fun decode(bytes: ByteArray): PlaintextPayload {
        val jsonText = bytes.decodeToString()
        val payload = ProtocolJson.json.decodeFromString(PlaintextPayload.serializer(), jsonText)
        validate(payload)
        return payload.copy(padding = null)
    }

    private fun validate(payload: PlaintextPayload) {
        val reason =
            when {
                !ProtocolVersion.isSupported(payload.v) -> "Unsupported protocol version: ${payload.v}"
                payload.kind !in validKinds -> "Invalid message kind: ${payload.kind}"
                payload.messageId.isBlank() -> "Blank messageId"
                payload.conversationId.isBlank() -> "Blank conversationId"
                payload.sentAt <= 0 -> "Invalid sentAt: ${payload.sentAt}"
                else -> null
            }
        if (reason != null) {
            throw SerializationException(reason)
        }
    }
}
