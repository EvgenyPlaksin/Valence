package com.lnight.valence.server

import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.protocol.http.AccessTokenResponse
import com.lnight.valence.protocol.http.RegisterRequest
import com.lnight.valence.server.domain.PublicKeyVerifier
import com.lnight.valence.server.store.InMemoryMailboxStore
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class RegistrationRoutesTest {
    private fun ApplicationTestBuilder.start(verifier: PublicKeyVerifier = FakePublicKeyVerifier()) {
        val service = RegistrationTestSupport.service(verifier = verifier, store = InMemoryMailboxStore())
        application { module(ChallengeTestSupport.service(), service) }
    }

    private suspend fun HttpClient.register(body: String): HttpResponse =
        post(ProtocolRoutes.REGISTER) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun json(request: RegisterRequest): String =
        ProtocolJson.json.encodeToString(RegisterRequest.serializer(), request)

    @Test
    fun `a valid registration answers 201 with an access token`() =
        testApplication {
            start()

            val response = client.register(json(RegistrationTestSupport.registerRequest()))

            assertEquals(HttpStatusCode.Created, response.status)
            val body = ProtocolJson.json.decodeFromString(AccessTokenResponse.serializer(), response.bodyAsText())
            assertEquals("token-for-${RegistrationTestSupport.MAILBOX_ID}", body.accessToken)
            assertEquals(1, body.v)
            assertEquals(RegistrationTestSupport.TOKEN_EXPIRY_MILLIS, body.expiresAt)
        }

    @Test
    fun `registering the same mailbox twice answers 409`() =
        testApplication {
            start()
            val body = json(RegistrationTestSupport.registerRequest())

            client.register(body)

            assertEquals(HttpStatusCode.Conflict, client.register(body).status)
        }

    @Test
    fun `an invalid proof answers 401`() =
        testApplication {
            start(FakePublicKeyVerifier(isSignatureValid = { !it.contentEquals(RegistrationTestSupport.validProof) }))

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.register(json(RegistrationTestSupport.registerRequest())).status,
            )
        }

    @Test
    fun `a body that is not json answers 400`() =
        testApplication {
            start()

            assertEquals(HttpStatusCode.BadRequest, client.register("not json").status)
        }

    @Test
    fun `an unsupported version answers 400`() =
        testApplication {
            start()
            val request = RegistrationTestSupport.registerRequest().copy(v = 99)

            assertEquals(HttpStatusCode.BadRequest, client.register(json(request)).status)
        }

    @Test
    fun `text that is not base64url answers 400`() =
        testApplication {
            start()
            val request = RegistrationTestSupport.registerRequest().copy(identityKey = "!!!")

            assertEquals(HttpStatusCode.BadRequest, client.register(json(request)).status)
        }
}
