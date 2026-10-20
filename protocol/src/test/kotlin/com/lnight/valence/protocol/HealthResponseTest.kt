package com.lnight.valence.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HealthResponseTest {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
        }

    @Test
    fun serializesToMatchGoldenFixture() {
        val fixtureStream = javaClass.getResourceAsStream("/fixtures/health.json")
        assertNotNull(fixtureStream)
        val fixtureContent = fixtureStream.bufferedReader().use { it.readText() }

        val response = HealthResponse(version = 1, status = "ok")
        val serialized = json.encodeToString(response)

        val expected = json.encodeToString(json.decodeFromString<HealthResponse>(fixtureContent))
        assertEquals(expected, serialized)
    }
}
