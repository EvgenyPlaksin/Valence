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
import com.lnight.valence.protocol.http.UploadOneTimeKyberPreKeysRequest
import com.lnight.valence.protocol.http.UploadOneTimePreKeysRequest
import com.lnight.valence.server.RegistrationTestSupport.MAILBOX_ID
import com.lnight.valence.server.domain.OneTimeKyberPreKey
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
        oneTimeKyberCount: Int = 0,
    ): InMemoryMailboxStore {
        val keys = (1..oneTimeCount).map { OneTimePreKey(it, ByteArray(33) { 7 }) }
        val kyberKeys =
            RegistrationTestSupport.defaultOneTimeKyberPreKeys().take(oneTimeKyberCount)
        val store = InMemoryMailboxStore().seed(RegistrationTestSupport.mailbox(), keys, kyberKeys)
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

    private suspend fun HttpClient.uploadKyber(
        body: String,
        bearer: String? = token,
    ): HttpResponse =
        post(ProtocolRoutes.KEYS_KYBER_ONE_TIME) {
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

    private fun uploadKyberJson(ids: IntRange): String =
        ProtocolJson.json.encodeToString(
            UploadOneTimeKyberPreKeysRequest.serializer(),
            UploadOneTimeKyberPreKeysRequest(
                oneTimeKyberPreKeys =
                    ids.map {
                        KyberPreKeyDto(
                            it,
                            Base64Url.encode(ByteArray(1569) { 6 }),
                            Base64Url.encode(RegistrationTestSupport.kyberPreKeySignature),
                        )
                    },
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
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.uploadKyber(uploadKyberJson(103..104), bearer = null).status,
            )
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
    fun `replacing a last resort id already used by a one time kyber key answers 409`() =
        testApplication {
            start(oneTimeKyberCount = 1)

            assertEquals(HttpStatusCode.Conflict, client.replace(replaceJson(kyberId = 101)).status)
        }

    @Test
    fun `unusable replacement bodies answer 400`() =
        testApplication {
            start()
            val unsupported = replaceJson().replace("\"v\":2", "\"v\":99")

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
            assertEquals(0, body.oneTimeKyberRemaining)
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

    @Test
    fun `uploading one time kyber keys answers 200 with both counts`() =
        testApplication {
            start()

            val response = client.uploadKyber(uploadKyberJson(103..104))

            assertEquals(HttpStatusCode.OK, response.status)
            val body = ProtocolJson.json.decodeFromString(KeyStatusResponse.serializer(), response.bodyAsText())
            assertEquals(2, body.oneTimeRemaining)
            assertEquals(2, body.oneTimeKyberRemaining)
        }

    @Test
    fun `one time kyber upload collisions answer 409`() =
        testApplication {
            start()

            assertEquals(HttpStatusCode.Conflict, client.uploadKyber(uploadKyberJson(1..1)).status)
        }

    @Test
    fun `one time kyber upload conflicts with an existing id`() =
        testApplication {
            start(oneTimeKyberCount = 1)

            assertEquals(HttpStatusCode.Conflict, client.uploadKyber(uploadKyberJson(101..101)).status)
        }

    @Test
    fun `invalid one time kyber signatures answer 400`() =
        testApplication {
            start(FakePublicKeyVerifier(isSignatureValid = { false }))

            assertEquals(HttpStatusCode.BadRequest, client.uploadKyber(uploadKyberJson(103..104)).status)
        }

    @Test
    fun `one time kyber signatures are required`() =
        testApplication {
            start(FakePublicKeyVerifier(isSignatureValid = { false }))

            assertEquals(HttpStatusCode.BadRequest, client.uploadKyber(uploadKyberJson(103..104)).status)
        }

    @Test
    fun `last resort replacement cannot reuse a stored one time kyber id`() =
        testApplication {
            start(oneTimeKyberCount = 1)

            assertEquals(HttpStatusCode.Conflict, client.replace(replaceJson(kyberId = 101)).status)
        }

    @Test
    fun `unusable one time kyber upload bodies answer 400`() =
        testApplication {
            start()
            val unsupported = uploadKyberJson(103..104).replace("\"v\":2", "\"v\":99")

            assertEquals(HttpStatusCode.BadRequest, client.uploadKyber("not json").status)
            assertEquals(HttpStatusCode.BadRequest, client.uploadKyber(unsupported).status)
        }
}
