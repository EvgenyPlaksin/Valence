package com.lnight.valence.protocol

import com.lnight.valence.protocol.plaintext.AttachmentRef
import com.lnight.valence.protocol.plaintext.PlaintextCodec
import com.lnight.valence.protocol.plaintext.PlaintextPayload
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaintextCodecTest {
    @Test
    fun `encoded plaintext is always a multiple of 256 bytes`() {
        val payload =
            PlaintextPayload(
                kind = "text",
                messageId = "msg_01",
                sentAt = 1_760_000_000_000L,
                conversationId = "conv_01",
                body = "Hello",
            )
        val encoded = PlaintextCodec.encode(payload)
        assertEquals(0, encoded.size % 256)
        assertEquals(256, encoded.size)

        val decoded = PlaintextCodec.decode(encoded)
        assertEquals(payload.messageId, decoded.messageId)
        assertEquals(payload.body, decoded.body)
        assertNull(decoded.padding)
    }

    @Test
    fun `boundary test around 255, 256, 257 utf-8 bytes`() {
        for (len in 100..400) {
            val body = "a".repeat(len)
            val payload =
                PlaintextPayload(
                    kind = "text",
                    messageId = "msg_boundary_$len",
                    sentAt = 1_760_000_000_000L,
                    conversationId = "conv_01",
                    body = body,
                )
            val encoded = PlaintextCodec.encode(payload)
            assertEquals(0, encoded.size % 256, "Failed for length $len")
            val decoded = PlaintextCodec.decode(encoded)
            assertEquals(body, decoded.body)
        }
    }

    @Test
    fun `multi-byte utf-8 characters pad and decode accurately`() {
        val multiByteText = "Grüße! Привет 🚀 \uD83D\uDC4B \uD83C\uDF0D \uD83D\uDD12"
        val payload =
            PlaintextPayload(
                kind = "text",
                messageId = "msg_mb",
                sentAt = 1_760_000_000_000L,
                conversationId = "conv_01",
                body = multiByteText,
            )
        val encoded = PlaintextCodec.encode(payload)
        assertEquals(0, encoded.size % 256)
        val decoded = PlaintextCodec.decode(encoded)
        assertEquals(multiByteText, decoded.body)
    }

    @Test
    fun `payload with attachments encodes and decodes`() {
        val attachments =
            listOf(
                AttachmentRef(
                    blobId = "blob_1",
                    key = "key_1",
                    contentType = "image/png",
                    sizeBytes = 1024L,
                    fileName = "photo.png",
                ),
            )
        val payload =
            PlaintextPayload(
                kind = "attachment",
                messageId = "msg_att",
                sentAt = 1_760_000_000_000L,
                conversationId = "conv_01",
                attachments = attachments,
            )
        val encoded = PlaintextCodec.encode(payload)
        assertEquals(0, encoded.size % 256)
        val decoded = PlaintextCodec.decode(encoded)
        assertEquals(attachments, decoded.attachments)
    }

    @Test
    fun `decode tolerates non-256 byte padding leniently`() {
        val json =
            """
            {"v":2,"kind":"text","messageId":"m1","sentAt":100,"conversationId":"c1","body":"hi","padding":"123"}
            """.trimIndent()
        val bytes = json.encodeToByteArray()
        assertTrue(bytes.size % 256 != 0)
        val decoded = PlaintextCodec.decode(bytes)
        assertEquals("m1", decoded.messageId)
        assertEquals("hi", decoded.body)
        assertNull(decoded.padding)
    }

    @Test
    fun `decode rejects unsupported version`() {
        val json = """{"v":99,"kind":"text","messageId":"m1","sentAt":100,"conversationId":"c1"}"""
        assertFailsWith<SerializationException> {
            PlaintextCodec.decode(json.encodeToByteArray())
        }
    }

    @Test
    fun `decode rejects invalid kind`() {
        val json = """{"v":2,"kind":"unknown_kind","messageId":"m1","sentAt":100,"conversationId":"c1"}"""
        assertFailsWith<SerializationException> {
            PlaintextCodec.decode(json.encodeToByteArray())
        }
    }

    @Test
    fun `decode rejects blank messageId`() {
        val json = """{"v":2,"kind":"text","messageId":"   ","sentAt":100,"conversationId":"c1"}"""
        assertFailsWith<SerializationException> {
            PlaintextCodec.decode(json.encodeToByteArray())
        }
    }

    @Test
    fun `decode rejects blank conversationId`() {
        val json = """{"v":2,"kind":"text","messageId":"m1","sentAt":100,"conversationId":""}"""
        assertFailsWith<SerializationException> {
            PlaintextCodec.decode(json.encodeToByteArray())
        }
    }

    @Test
    fun `decode rejects invalid sentAt`() {
        val json = """{"v":2,"kind":"text","messageId":"m1","sentAt":0,"conversationId":"c1"}"""
        assertFailsWith<SerializationException> {
            PlaintextCodec.decode(json.encodeToByteArray())
        }
    }
}
