package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.GoldenFixtures
import com.lnight.valence.protocol.ProtocolHeaders
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.http.SendMessageRequest
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.store.InMemoryInboxStore
import com.lnight.valence.server.store.InMemoryMailboxStore
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageRoutesTest {
    private val id = RegistrationTestSupport.MAILBOX_ID
    private val deliveryKey = Base64Url.encode(DeliveryKeyTestSupport.deliveryKey)
    private val fixtureTs = 1_760_000_000_123L

    private fun ApplicationTestBuilder.start(inbox: InMemoryInboxStore = InMemoryInboxStore()): InMemoryInboxStore {
        val store = InMemoryMailboxStore().also { it.create(DeliveryKeyTestSupport.mailbox(), emptyList()) }
        application { module(TestServices.create(store = store, inbox = inbox, atMillis = fixtureTs)) }
        return inbox
    }

    private suspend fun HttpClient.send(
        body: String,
        key: String? = deliveryKey,
        mailboxId: String = id,
    ): HttpResponse =
        post("/v1/messages/$mailboxId") {
            key?.let { header(ProtocolHeaders.DELIVERY_KEY, it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun request(
        envelope: String = "u8F2Q0xWm3sA",
        ttl: Long? = 2_592_000L,
    ): String =
        ProtocolJson.json.encodeToString(
            SendMessageRequest.serializer(),
            SendMessageRequest(envelope = envelope, ttlSeconds = ttl),
        )

    @Test
    fun `the protocol fixture request is accepted and answered like the fixture`() =
        testApplication {
            val inbox = start()

            val response = client.send(GoldenFixtures.read("send-message-request"))

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(
                ProtocolJson.json.parseToJsonElement(GoldenFixtures.read("send-message-response")),
                ProtocolJson.json.parseToJsonElement(response.bodyAsText()),
            )
            val message = inbox.messagesAfter(id, 0).single()
            assertContentEquals(Base64Url.decode("u8F2Q0xWm3sA"), message.envelope)
            assertEquals(fixtureTs + 2_592_000_000L, message.expiresAtMillis)
        }

    @Test
    fun `consecutive sends receive consecutive sequence numbers`() =
        testApplication {
            val inbox = start()

            client.send(request())
            client.send(request())

            assertEquals(listOf(1L, 2L), inbox.messagesAfter(id, 0).map { it.serverSeq })
        }

    @Test
    fun `a wrong key a missing key and an unknown mailbox are indistinguishable`() =
        testApplication {
            val inbox = start()

            val wrongKey = client.send(request(), key = Base64Url.encode(ByteArray(16) { 4 }))
            val missing = client.send(request(), key = null)
            val malformed = client.send(request(), key = "!!!")
            val unknown = client.send(request(), mailboxId = "mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa")

            listOf(wrongKey, missing, malformed, unknown).forEach {
                assertEquals(HttpStatusCode.Unauthorized, it.status)
                assertEquals("", it.bodyAsText())
            }
            assertTrue(inbox.messagesAfter(id, 0).isEmpty())
        }

    @Test
    fun `unusable bodies answer 400`() =
        testApplication {
            start()
            val unsupported = request().replace("\"v\":1", "\"v\":99")

            assertEquals(HttpStatusCode.BadRequest, client.send("not json").status)
            assertEquals(HttpStatusCode.BadRequest, client.send(unsupported).status)
            assertEquals(HttpStatusCode.BadRequest, client.send(request(envelope = "!!!")).status)
            assertEquals(HttpStatusCode.BadRequest, client.send(request(envelope = "")).status)
            assertEquals(HttpStatusCode.BadRequest, client.send(request(ttl = 0L)).status)
        }

    @Test
    fun `an envelope over 64 KiB answers 413`() =
        testApplication {
            val inbox = start()

            val response = client.send(request(envelope = Base64Url.encode(ByteArray(65_537))))

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(inbox.messagesAfter(id, 0).isEmpty())
        }

    @Test
    fun `a body over the limit answers 413 with and without a declared length`() =
        testApplication {
            start()

            val declared = client.send("x".repeat(100_000))
            val undeclared =
                client.post("/v1/messages/$id") {
                    header(ProtocolHeaders.DELIVERY_KEY, deliveryKey)
                    contentType(ContentType.Application.Json)
                    setBody(ByteReadChannel(ByteArray(100_000) { 'x'.code.toByte() }))
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, declared.status)
            assertEquals(HttpStatusCode.PayloadTooLarge, undeclared.status)
        }

    @Test
    fun `a full mailbox answers 429 with a retry hint`() =
        testApplication {
            val inbox = InMemoryInboxStore()
            repeat(1_000) { inbox.append(id, NewMessage(ByteArray(1), fixtureTs, fixtureTs + 86_400_000L), 1_000) }
            start(inbox)

            val response = client.send(request())

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("300", response.headers[HttpHeaders.RetryAfter])
        }
}
