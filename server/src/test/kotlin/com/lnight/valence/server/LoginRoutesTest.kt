package com.lnight.valence.server

import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.protocol.http.AccessTokenResponse
import com.lnight.valence.protocol.http.AuthTokenRequest
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

class LoginRoutesTest {
    private fun ApplicationTestBuilder.start(verifier: PublicKeyVerifier = FakePublicKeyVerifier()) {
        val store = InMemoryMailboxStore().also { it.create(RegistrationTestSupport.mailbox(), emptyList()) }
        application {
            module(
                ChallengeTestSupport.service(),
                RegistrationTestSupport.service(verifier = verifier, store = store),
                LoginTestSupport.service(verifier = verifier, store = store),
            )
        }
    }

    private suspend fun HttpClient.login(body: String): HttpResponse =
        post(ProtocolRoutes.AUTH_TOKEN) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun json(request: AuthTokenRequest): String =
        ProtocolJson.json.encodeToString(AuthTokenRequest.serializer(), request)

    @Test
    fun `a valid login answers 200 with an access token`() =
        testApplication {
            start()

            val response = client.login(json(RegistrationTestSupport.authTokenRequest()))

            assertEquals(HttpStatusCode.OK, response.status)
            val body = ProtocolJson.json.decodeFromString(AccessTokenResponse.serializer(), response.bodyAsText())
            assertEquals("token-for-${RegistrationTestSupport.MAILBOX_ID}", body.accessToken)
            assertEquals(1, body.v)
        }

    @Test
    fun `an invalid signature answers 401`() =
        testApplication {
            start(FakePublicKeyVerifier(isSignatureValid = { false }))

            assertEquals(
                HttpStatusCode.Unauthorized,
                client.login(json(RegistrationTestSupport.authTokenRequest())).status,
            )
        }

    @Test
    fun `an unknown mailbox and an invalid signature are indistinguishable`() =
        testApplication {
            start(FakePublicKeyVerifier(isSignatureValid = { false }))
            val known = RegistrationTestSupport.authTokenRequest()
            val unknown = known.copy(mailboxId = "mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa")

            val knownResponse = client.login(json(known))
            val unknownResponse = client.login(json(unknown))

            assertEquals(knownResponse.status, unknownResponse.status)
            assertEquals(knownResponse.bodyAsText(), unknownResponse.bodyAsText())
        }

    @Test
    fun `a body that is not json answers 400`() =
        testApplication {
            start()

            assertEquals(HttpStatusCode.BadRequest, client.login("not json").status)
        }

    @Test
    fun `an unsupported version answers 400`() =
        testApplication {
            start()
            val request = RegistrationTestSupport.authTokenRequest().copy(v = 99)

            assertEquals(HttpStatusCode.BadRequest, client.login(json(request)).status)
        }

    @Test
    fun `text that is not base64url answers 400`() =
        testApplication {
            start()
            val request = RegistrationTestSupport.authTokenRequest().copy(signature = "!!!")

            assertEquals(HttpStatusCode.BadRequest, client.login(json(request)).status)
        }
}
