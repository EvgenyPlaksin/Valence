package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.ProtocolHeaders
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.protocol.frame.DeliveryConfirmFrame
import com.lnight.valence.protocol.frame.SyncResumeFrame
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.domain.RateLimitPolicies
import com.lnight.valence.server.domain.RateLimitPolicy
import com.lnight.valence.server.store.InMemoryInboxStore
import com.lnight.valence.server.store.InMemoryMailboxStore
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MetricsRoutesTest {
    private val id = RegistrationTestSupport.MAILBOX_ID
    private val deliveryKey = Base64Url.encode(DeliveryKeyTestSupport.deliveryKey)
    private val token = "metrics-token-123456"
    private val now = ChallengeTestSupport.ISSUED_AT_MILLIS
    private val generous = RateLimitPolicies(otherPerAddress = RateLimitPolicy(burst = 1_000, refillPerSecond = 10.0))

    private fun ApplicationTestBuilder.start(
        metricsToken: String? = token,
        messages: Int = 0,
    ) {
        val inbox = InMemoryInboxStore()
        (1..messages).forEach { inbox.append(id, NewMessage(byteArrayOf(it.toByte()), now, now + 60_000L), 1_000) }
        val store = InMemoryMailboxStore().also { it.create(DeliveryKeyTestSupport.mailbox(), emptyList()) }
        application {
            module(TestServices.create(store = store, inbox = inbox, policies = generous, metricsToken = metricsToken))
        }
    }

    private suspend fun io.ktor.client.HttpClient.metrics(bearer: String? = token): HttpResponse =
        get("/metrics") { bearer?.let { bearerAuth(it) } }

    private suspend fun io.ktor.client.HttpClient.awaitMetrics(vararg expected: String): String =
        withTimeout(5.seconds) {
            var body = metrics().bodyAsText()
            while (!expected.all { body.contains(it) }) {
                delay(20)
                body = metrics().bodyAsText()
            }
            body
        }

    @Test
    fun `without a configured token the endpoint does not exist`() =
        testApplication {
            start(metricsToken = null)

            assertEquals(HttpStatusCode.NotFound, client.metrics().status)
        }

    @Test
    fun `a wrong or missing token answers 401`() =
        testApplication {
            start()

            assertEquals(HttpStatusCode.Unauthorized, client.metrics(bearer = null).status)
            assertEquals(HttpStatusCode.Unauthorized, client.metrics(bearer = "wrong-token-1234567").status)
        }

    @Test
    fun `responses are counted by scope and status`() =
        testApplication {
            start()
            repeat(2) { client.post(ProtocolRoutes.AUTH_CHALLENGE) }
            client.get("/health")
            client.post("/v1/messages/$id") {
                header(ProtocolHeaders.DELIVERY_KEY, "AAAA")
                contentType(ContentType.Application.Json)
                setBody("""{"v":1,"envelope":"u8F2Q0xWm3sA"}""")
            }

            val body =
                client.awaitMetrics(
                    """valence_http_responses_total{scope="challenge",status="200"} 2""",
                    """valence_http_responses_total{scope="health",status="200"} 1""",
                    """valence_http_responses_total{scope="send",status="401"} 1""",
                )

            assertTrue(body.contains("# TYPE valence_http_responses_total counter"))
        }

    @Test
    fun `the gauges show the queue depth and the active connections`() =
        testApplication {
            start(messages = 3)

            val body = client.awaitMetrics("valence_queue_depth 3", "valence_active_connections 0")

            assertTrue(body.contains("# TYPE valence_queue_depth gauge"))
        }

    @Test
    fun `the metrics never contain identifiers or keys`() =
        testApplication {
            start()
            client.post("/v1/messages/$id") {
                header(ProtocolHeaders.DELIVERY_KEY, deliveryKey)
                contentType(ContentType.Application.Json)
                setBody("""{"v":1,"envelope":"u8F2Q0xWm3sA"}""")
            }
            client.get("/v1/prekeys/$id") { header(ProtocolHeaders.DELIVERY_KEY, deliveryKey) }
            client.get(ProtocolRoutes.KEYS_STATUS) { bearerAuth(FakeAccessTokens.bearer(id)) }

            val body = client.awaitMetrics("""scope="bundle"""", """scope="keys"""", """scope="send"""")

            assertFalse(body.contains(id))
            assertFalse(body.contains("mbx_"))
            assertFalse(body.contains(deliveryKey))
            assertFalse(body.contains(FakeAccessTokens.bearer(id)))
        }

    @Test
    fun `socket activity is counted`() =
        testApplication {
            start(messages = 2)
            val client = createClient { install(WebSockets) }

            client.webSocket("/v1/socket", request = { bearerAuth(FakeAccessTokens.bearer(id)) }) {
                send(Frame.Text(ProtocolJson.encodeClientFrame(SyncResumeFrame(afterServerSeq = 0))))
                repeat(2) { withTimeout(5.seconds) { incoming.receive() } }
                send(Frame.Text("not json"))
                withTimeout(5.seconds) { incoming.receive() }
                send(Frame.Text(ProtocolJson.encodeClientFrame(DeliveryConfirmFrame(upToServerSeq = 2))))

                val body =
                    client.awaitMetrics(
                        "valence_socket_connections_opened_total 1",
                        "valence_messages_delivered_total 2",
                        "valence_invalid_frames_total 1",
                        "valence_active_connections 1",
                    )

                assertTrue(body.contains("valence_queue_depth"))
            }
        }
}
