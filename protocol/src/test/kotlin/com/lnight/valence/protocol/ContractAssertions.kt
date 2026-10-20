package com.lnight.valence.protocol

import kotlinx.serialization.KSerializer
import kotlin.test.assertEquals

internal object ContractAssertions {
    fun <T> roundTrip(
        fixture: String,
        serializer: KSerializer<T>,
    ): T {
        val text = GoldenFixtures.read(fixture)
        val decoded = ProtocolJson.json.decodeFromString(serializer, text)
        val encoded = ProtocolJson.json.encodeToJsonElement(serializer, decoded)

        assertEquals(ProtocolJson.json.parseToJsonElement(text), encoded)
        return decoded
    }
}