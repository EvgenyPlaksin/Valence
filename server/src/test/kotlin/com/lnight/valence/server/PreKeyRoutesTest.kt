package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.ProtocolHeaders
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.http.PreKeyBundleResponse
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.store.InMemoryMailboxStore
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PreKeyRoutesTest {
    private val id = RegistrationTestSupport.MAILBOX_ID
    private val deliveryKey = Base64Url.encode(DeliveryKeyTestSupport.deliveryKey)

    private fun ApplicationTestBuilder.start(oneTimeCount: Int = 2): InMemoryMailboxStore {
        val keys = (1..oneTimeCount).map { OneTimePreKey(it, ByteArray(33) { 7 }) }
        val store = InMemoryMailboxStore().also { it.create(DeliveryKeyTestSupport.mailbox(), keys) }
        application { module(TestServices.create(store = store)) }
        return store
    }

    private suspend fun HttpClient.fetch(
        mailboxId: String = id,
        key: String? = deliveryKey,
        bearer: String? = null,
    ): HttpResponse =
        get("/v1/prekeys/$mailboxId") {
            key?.let { header(ProtocolHeaders.DELIVERY_KEY, it) }
            bearer?.let { bearerAuth(it) }
        }

    private suspend fun HttpResponse.bundle() =
        ProtocolJson.json.decodeFromString(PreKeyBundleResponse.serializer(), bodyAsText())

    @Test
    fun `a delivery key fetch answers 200 with the bundle and needs no token`() =
        testApplication {
            start()

            val response = client.fetch()

            assertEquals(HttpStatusCode.OK, response.status)
            val bundle = response.bundle()
            assertEquals(1, bundle.v)
            assertContentEquals(ByteArray(33) { 4 }, Base64Url.decode(bundle.identityKey))
            assertEquals(1, bundle.signedPreKey.id)
            assertEquals(1, bundle.kyberPreKey.id)
            assertEquals(1, bundle.oneTimePreKey?.id)
        }

    @Test
    fun `fetches consume one time keys and an exhausted bundle omits the field`() =
        testApplication {
            val store = start()

            assertEquals(
                1,
                client
                    .fetch()
                    .bundle()
                    .oneTimePreKey
                    ?.id,
            )
            assertEquals(
                2,
                client
                    .fetch()
                    .bundle()
                    .oneTimePreKey
                    ?.id,
            )
            val exhausted = client.fetch()

            assertEquals(HttpStatusCode.OK, exhausted.status)
            assertFalse(
                ProtocolJson.json
                    .parseToJsonElement(exhausted.bodyAsText())
                    .jsonObject
                    .containsKey("oneTimePreKey"),
            )
            assertNull(exhausted.bundle().oneTimePreKey)
            assertEquals(0, store.oneTimePreKeyCount(id))
        }

    @Test
    fun `a wrong delivery key answers 401 and consumes nothing`() =
        testApplication {
            val store = start()

            val response = client.fetch(key = Base64Url.encode(ByteArray(16) { 4 }))

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(2, store.oneTimePreKeyCount(id))
        }

    @Test
    fun `a missing or malformed delivery key answers 401`() =
        testApplication {
            start()

            assertEquals(HttpStatusCode.Unauthorized, client.fetch(key = null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.fetch(key = "!!!").status)
        }

    @Test
    fun `an access token is not a substitute for the delivery key`() =
        testApplication {
            start()

            val response = client.fetch(key = null, bearer = FakeAccessTokens.bearer(id))

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `an unknown mailbox answers 401 like a wrong key`() =
        testApplication {
            start()

            val unknown = client.fetch(mailboxId = "mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa")
            val wrongKey = client.fetch(key = Base64Url.encode(ByteArray(16) { 4 }))
            val malformedId = client.fetch(mailboxId = "nonsense")

            assertEquals(HttpStatusCode.Unauthorized, unknown.status)
            assertEquals(wrongKey.status, unknown.status)
            assertEquals(wrongKey.bodyAsText(), unknown.bodyAsText())
            assertEquals(HttpStatusCode.Unauthorized, malformedId.status)
        }
}
