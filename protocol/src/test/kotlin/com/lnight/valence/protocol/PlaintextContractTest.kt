package com.lnight.valence.protocol

import com.lnight.valence.protocol.plaintext.PlaintextCodec
import com.lnight.valence.protocol.plaintext.PlaintextPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaintextContractTest {
    @Test
    fun `plaintext text payload matches its fixture`() {
        val payload = ContractAssertions.roundTrip("plaintext-text", PlaintextPayload.serializer())
        assertEquals("text", payload.kind)
        assertEquals("msg_01jh8s92n4", payload.messageId)
        assertEquals("Hello, world!", payload.body)
    }

    @Test
    fun `plaintext no body matches its fixture`() {
        val payload = ContractAssertions.roundTrip("plaintext-no-body", PlaintextPayload.serializer())
        assertEquals("system", payload.kind)
        assertEquals("msg_01jh8s92sys", payload.messageId)
        assertNull(payload.body)
    }

    @Test
    fun `plaintext padded 256 matches its fixture and decodes cleanly`() {
        val text = GoldenFixtures.read("plaintext-padded-256")
        assertEquals(256, text.encodeToByteArray().size)

        val payload = ContractAssertions.roundTrip("plaintext-padded-256", PlaintextPayload.serializer())
        assertEquals("Hello, world!", payload.body)

        val decoded = PlaintextCodec.decode(text.encodeToByteArray())
        assertEquals("Hello, world!", decoded.body)
        assertNull(decoded.padding)
    }

    @Test
    fun `plaintext padded 512 matches its fixture and decodes cleanly`() {
        val text = GoldenFixtures.read("plaintext-padded-512")
        assertEquals(512, text.encodeToByteArray().size)

        val payload = ContractAssertions.roundTrip("plaintext-padded-512", PlaintextPayload.serializer())
        assertEquals(200, payload.body?.length)

        val decoded = PlaintextCodec.decode(text.encodeToByteArray())
        assertEquals(200, decoded.body?.length)
        assertNull(decoded.padding)
    }

    @Test
    fun `plaintext unknown field is ignored on decode`() {
        val text = GoldenFixtures.read("plaintext-unknown-field")
        val decoded = PlaintextCodec.decode(text.encodeToByteArray())
        assertEquals("msg_01jh8s92n4", decoded.messageId)
        assertEquals("Hello, world!", decoded.body)
    }

    @Test
    fun `v1 plaintext payload decodes successfully`() {
        val json = """{"v":1,"kind":"text","messageId":"m1","sentAt":100,"conversationId":"c1","body":"v1 message"}"""
        val decoded = PlaintextCodec.decode(json.encodeToByteArray())
        assertEquals(1, decoded.v)
        assertEquals("v1 message", decoded.body)
    }
}
