package com.lnight.valence.core.network.plaintext

import com.lnight.valence.core.model.ConversationId
import com.lnight.valence.core.model.MessageId
import com.lnight.valence.core.model.MessageKind
import com.lnight.valence.core.model.Plaintext
import com.lnight.valence.core.model.PlaintextCodecFailure
import com.lnight.valence.core.model.PlaintextCodecResult
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.plaintext.AttachmentRef
import com.lnight.valence.protocol.plaintext.PlaintextPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaintextCodecImplTest {
    private val codec = PlaintextCodecImpl(PlaintextPayloadMapper())

    @Test
    fun `encodes and decodes a plaintext payload`() {
        val plaintext = plaintext()

        val encoded = assertSuccess(codec.encode(plaintext))
        assertEquals(plaintext, assertSuccess(codec.decode(encoded)))
    }

    @Test
    fun `encoded plaintext is padded to a 256 byte boundary`() {
        val encoded = assertSuccess(codec.encode(plaintext(body = "a".repeat(220))))

        assertEquals(0, encoded.size % 256)
    }

    @Test
    fun `round trips multi byte utf8 text`() {
        val plaintext = plaintext(body = "Grüße! Привет 🚀")

        assertEquals(plaintext, assertSuccess(codec.decode(assertSuccess(codec.encode(plaintext)))))
    }

    @Test
    fun `rejects malformed utf8 bytes`() {
        assertFailure(
            PlaintextCodecFailure.MalformedUtf8,
            codec.decode(byteArrayOf(0xC3.toByte(), 0x28)),
        )
    }

    @Test
    fun `rejects malformed json`() {
        assertFailure(PlaintextCodecFailure.InvalidPayload, codec.decode("{".encodeToByteArray()))
    }

    @Test
    fun `rejects unsupported protocol version`() {
        assertFailure(
            PlaintextCodecFailure.InvalidPayload,
            decode("""{"v":99,"kind":"text","messageId":"m1","sentAt":100,"conversationId":"c1"}"""),
        )
    }

    @Test
    fun `rejects unknown message kind`() {
        assertFailure(
            PlaintextCodecFailure.InvalidPayload,
            decode("""{"v":2,"kind":"unknown","messageId":"m1","sentAt":100,"conversationId":"c1"}"""),
        )
    }

    @Test
    fun `rejects blank message id`() {
        assertFailure(
            PlaintextCodecFailure.InvalidPayload,
            decode("""{"v":2,"kind":"text","messageId":" ","sentAt":100,"conversationId":"c1"}"""),
        )
    }

    @Test
    fun `rejects blank conversation id`() {
        assertFailure(
            PlaintextCodecFailure.InvalidPayload,
            decode("""{"v":2,"kind":"text","messageId":"m1","sentAt":100,"conversationId":""}"""),
        )
    }

    @Test
    fun `rejects non positive sent at`() {
        assertFailure(
            PlaintextCodecFailure.InvalidPayload,
            decode("""{"v":2,"kind":"text","messageId":"m1","sentAt":0,"conversationId":"c1"}"""),
        )
    }

    @Test
    fun `rejects unsupported attachment references without losing them`() {
        val payload =
            PlaintextPayload(
                kind = "attachment",
                messageId = "m1",
                sentAt = 100,
                conversationId = "c1",
                attachments =
                    listOf(
                        AttachmentRef(
                            blobId = "blob",
                            key = "key",
                            contentType = "image/png",
                            sizeBytes = 1,
                        ),
                    ),
            )
        val bytes = ProtocolJson.json.encodeToString(PlaintextPayload.serializer(), payload).encodeToByteArray()

        assertFailure(PlaintextCodecFailure.UnsupportedAttachments, codec.decode(bytes))
    }

    @Test
    fun `rejects invalid domain plaintext on encode`() {
        assertFailure(
            PlaintextCodecFailure.InvalidPayload,
            codec.encode(plaintext(messageId = " ")),
        )
    }

    private fun plaintext(
        kind: MessageKind = MessageKind.Text,
        messageId: String = "message-1",
        sentAt: Long = 1_760_000_000_000L,
        conversationId: String = "conversation-1",
        body: String? = "hello",
        replyToId: String? = null,
    ) = Plaintext(
        kind = kind,
        messageId = MessageId(messageId),
        sentAt = sentAt,
        conversationId = ConversationId(conversationId),
        body = body,
        replyToId = replyToId?.let(::MessageId),
    )

    private fun decode(json: String) = codec.decode(json.encodeToByteArray())

    private fun <T> assertSuccess(result: PlaintextCodecResult<T>): T =
        when (result) {
            is PlaintextCodecResult.Success -> result.value
            is PlaintextCodecResult.Failure -> throw AssertionError("Expected success but got $result")
        }

    private fun assertFailure(
        failure: PlaintextCodecFailure,
        result: PlaintextCodecResult<*>,
    ) {
        assertEquals(PlaintextCodecResult.Failure(failure), result)
    }
}
