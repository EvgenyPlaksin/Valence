package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.GoldenFixtures
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.protocol.http.KeyStatusResponse
import com.lnight.valence.protocol.http.KyberPreKeyDto
import com.lnight.valence.protocol.http.OneTimePreKeyDto
import com.lnight.valence.protocol.http.ReplaceSignedPreKeysRequest
import com.lnight.valence.protocol.http.SignedPreKeyDto
import com.lnight.valence.protocol.http.UploadOneTimePreKeysRequest
import com.lnight.valence.server.RegistrationTestSupport.MAILBOX_ID
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.PublicKeyVerifier
import com.lnight.valence.server.store.InMemoryMailboxStore
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
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

class KeyRoutesTest {
    private val token = FakeAccessTokens.bearer(MAILBOX_ID)

    private fun ApplicationTestBuilder.start(
        verifier: PublicKeyVerifier = FakePublicKeyVerifier(),
        oneTimeCount: Int = 2,
    ): InMemoryMailboxStore {
        val keys = (1..oneTimeCount).map { OneTimePreKey(it, ByteArray(33) { 7 }) }
        val store = InMemoryMailboxStore().also { it.create(RegistrationTestSupport.mailbox(), keys) }
        application { module(TestServices.create(verifier, store)) }
        return store
    }

    private suspend fun HttpClient.status(bearer: String? = token): HttpResponse =
        get(ProtocolRoutes.KEYS_STATUS) { bearer?.let { bearerAuth(it) } }

    private suspend fun HttpClient.replace(
        body: String,
        bearer: String? = token,
    ): HttpResponse =
        put(ProtocolRoutes.KEYS_SIGNED) {
            bearer?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.upload(
        body: String,
        bearer: String? = token,
    ): HttpResponse =
        post(ProtocolRoutes.KEYS_ONE_TIME) {
            bearer?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun replaceJson(
        signedId: Int = 2,
        kyberId: Int = 2,
    ): String =
        ProtocolJson.json.encodeToString(
            ReplaceSignedPreKeysRequest.serializer(),
            ReplaceSignedPreKeysRequest(
                signedPreKey =
                    SignedPreKeyDto(
                        signedId,
                        Base64Url.encode(ByteArray(33) { 5 }),
                        Base64Url.encode(RegistrationTestSupport.signedPreKeySignature),
                    ),
                kyberPreKey =
                    KyberPreKeyDto(
                        kyberId,
                        Base64Url.encode(ByteArray(1569) { 6 }),
                        Base64Url.encode(RegistrationTestSupport.kyberPreKeySignature),
                    ),
            ),
        )

    private fun uploadJson(ids: IntRange): String =
        ProtocolJson.json.encodeToString(
            UploadOneTimePreKeysRequest.serializer(),
            UploadOneTimePreKeysRequest(
                oneTimePreKeys = ids.map { OneTimePreKeyDto(it, Base64Url.encode(ByteArray(33) { 7 })) },
            ),
        )

    @Test
    fun `every key endpoint requires a valid token`() =
        testApplication {
            start()

            assertEquals(HttpStatusCode.Unauthorized, client.status(bearer = null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.status(bearer = "garbage").status)
            assertEquals(HttpStatusCode.Unauthorized, client.replace(replaceJson(), bearer = null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.upload(uploadJson(101..102), bearer = null).status)
        }

    @Test
    fun `a token for an unknown mailbox answers 401`() =
        testApplication {
            start()

            val response = client.status(bearer = FakeAccessTokens.bearer("mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa"))

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `status answers with the protocol fixture`() =
        testApplication {
            start(oneTimeCount = 17)

            val response = client.status()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                ProtocolJson.json.parseToJsonElement(GoldenFixtures.read("key-status-response")),
                ProtocolJson.json.parseToJsonElement(response.bodyAsText()),
            )
        }

    @Test
    fun `replacing signed prekeys answers 204 and stores them`() =
        testApplication {
            val store = start()

            assertEquals(HttpStatusCode.NoContent, client.replace(replaceJson()).status)
            assertEquals(2, store.find(MAILBOX_ID)?.signedPreKey?.id)
        }

    @Test
    fun `replacing with the stored id answers 409`() =
        testApplication {
            start()

            assertEquals(HttpStatusCode.Conflict, client.replace(replaceJson(signedId = 1)).status)
        }

    @Test
    fun `an invalid prekey signature answers 400`() =
        testApplication {
            start(FakePublicKeyVerifier(isSignatureValid = { false }))

            assertEquals(HttpStatusCode.BadRequest, client.replace(replaceJson()).status)
        }

    @Test
    fun `unusable replacement bodies answer 400`() =
        testApplication {
            start()
            val unsupported = replaceJson().replace("\"v\":1", "\"v\":99")

            assertEquals(HttpStatusCode.BadRequest, client.replace("not json").status)
            assertEquals(HttpStatusCode.BadRequest, client.replace(unsupported).status)
        }

    @Test
    fun `uploading one time keys answers 200 with the new count`() =
        testApplication {
            start()

            val response = client.upload(uploadJson(101..102))

            assertEquals(HttpStatusCode.OK, response.status)
            val body = ProtocolJson.json.decodeFromString(KeyStatusResponse.serializer(), response.bodyAsText())
            assertEquals(4, body.oneTimeRemaining)
        }

    @Test
    fun `uploading a stored id answers 409`() =
        testApplication {
            start()

            assertEquals(HttpStatusCode.Conflict, client.upload(uploadJson(2..3)).status)
        }

    @Test
    fun `uploading beyond the stored limit answers 400`() =
        testApplication {
            start(oneTimeCount = 150)

            assertEquals(HttpStatusCode.BadRequest, client.upload(uploadJson(151..210)).status)
        }

    @Test
    fun `an upload that is not json answers 400`() =
        testApplication {
            start()

            assertEquals(HttpStatusCode.BadRequest, client.upload("not json").status)
        }
}
