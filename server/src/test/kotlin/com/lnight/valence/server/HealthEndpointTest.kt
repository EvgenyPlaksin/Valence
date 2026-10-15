package com.lnight.valence.server

import com.lnight.valence.protocol.HealthResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HealthEndpointTest {

    private val json = Json {
        encodeDefaults = true
        prettyPrint = false
    }

    @Test
    fun healthEndpointReturnsOkStatusAndBodyMatchesProtocolFixture() = testApplication {
        application {
            module()
        }

        val client = createClient {
            install(ContentNegotiation) {
                json()
            }
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)

        val fixtureStream = javaClass.getResourceAsStream("/fixtures/health.json")
        assertNotNull(fixtureStream)
        val fixtureText = fixtureStream.bufferedReader().use { it.readText() }
        val expected = json.decodeFromString<HealthResponse>(fixtureText)

        val actual = response.body<HealthResponse>()
        assertEquals(expected, actual)
    }
}
