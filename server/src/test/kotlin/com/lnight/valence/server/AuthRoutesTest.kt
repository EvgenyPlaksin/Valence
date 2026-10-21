package com.lnight.valence.server

import com.lnight.valence.protocol.GoldenFixtures
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.server.routes.authRoutes
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthRoutesTest {
    @Test
    fun `challenge endpoint answers with the protocol fixture`() =
        testApplication {
            application {
                install(ContentNegotiation) { json(ProtocolJson.json) }
                routing { authRoutes(ChallengeTestSupport.service()) }
            }

            val response = client.post(ProtocolRoutes.AUTH_CHALLENGE)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                ProtocolJson.json.parseToJsonElement(GoldenFixtures.read("auth-challenge-response")),
                ProtocolJson.json.parseToJsonElement(response.bodyAsText()),
            )
        }
}
