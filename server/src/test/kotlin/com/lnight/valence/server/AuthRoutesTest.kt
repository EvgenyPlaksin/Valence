package com.lnight.valence.server

import com.lnight.valence.protocol.GoldenFixtures
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.ProtocolRoutes
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthRoutesTest {
    @Test
    fun `challenge endpoint answers with the protocol fixture`() =
        testApplication {
            application {
                module(ChallengeTestSupport.service(), RegistrationTestSupport.service(), LoginTestSupport.service())
            }

            val response = client.post(ProtocolRoutes.AUTH_CHALLENGE)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals(
                ProtocolJson.json.parseToJsonElement(GoldenFixtures.read("auth-challenge-response")),
                ProtocolJson.json.parseToJsonElement(response.bodyAsText()),
            )
        }
}
