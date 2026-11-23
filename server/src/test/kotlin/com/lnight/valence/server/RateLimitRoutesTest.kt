package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.ProtocolHeaders
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.server.domain.RateLimitPolicies
import com.lnight.valence.server.domain.RateLimitPolicy
import com.lnight.valence.server.store.InMemoryInboxStore
import com.lnight.valence.server.store.InMemoryMailboxStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
import kotlin.test.Test
import kotlin.test.assertEquals

class RateLimitRoutesTest {
    private val id = RegistrationTestSupport.MAILBOX_ID
    private val deliveryKey = Base64Url.encode(DeliveryKeyTestSupport.deliveryKey)
    private val clock = TestClock(ChallengeTestSupport.ISSUED_AT_MILLIS)

    private fun tight(burst: Int = 2) = RateLimitPolicy(burst = burst, refillPerSecond = 1.0)

    private fun ApplicationTestBuilder.start(
        policies: RateLimitPolicies = RateLimitPolicies(),
        trustProxy: Boolean = false,
        inbox: InMemoryInboxStore = InMemoryInboxStore(),
    ) {
        val store =
            InMemoryMailboxStore().seed(
                DeliveryKeyTestSupport.mailbox(),
                RegistrationTestSupport.defaultOneTimePreKeys(),
            )
        application {
            module(
                TestServices.create(
                    store = store,
                    inbox = inbox,
                    policies = policies,
                    trustProxyHeaders = trustProxy,
                    clock = clock,
                ),
            )
        }
    }

    private suspend fun HttpClient.challenge(forwardedFor: String? = null): HttpResponse =
        post(ProtocolRoutes.AUTH_CHALLENGE) { forwardedFor?.let { header(HttpHeaders.XForwardedFor, it) } }

    private suspend fun HttpClient.send(key: String = deliveryKey): HttpResponse =
        post("/v1/messages/$id") {
            header(ProtocolHeaders.DELIVERY_KEY, key)
            contentType(ContentType.Application.Json)
            setBody("""{"v":1,"envelope":"u8F2Q0xWm3sA"}""")
        }

    private suspend fun HttpClient.bundle(): HttpResponse =
        get("/v1/prekeys/$id") { header(ProtocolHeaders.DELIVERY_KEY, deliveryKey) }

    @Test
    fun `an address over its limit is answered 429 with a retry hint and no body`() =
        testApplication {
            start(RateLimitPolicies(challengePerAddress = tight()))

            assertEquals(HttpStatusCode.OK, client.challenge().status)
            assertEquals(HttpStatusCode.OK, client.challenge().status)
            val limited = client.challenge()

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("1", limited.headers[HttpHeaders.RetryAfter])
            assertEquals("", limited.bodyAsText())
        }

    @Test
    fun `the limit refills over time`() =
        testApplication {
            start(RateLimitPolicies(challengePerAddress = tight()))
            repeat(3) { client.challenge() }

            clock.advance(1_000L)

            assertEquals(HttpStatusCode.OK, client.challenge().status)
        }

    @Test
    fun `a limited request never reaches the route`() =
        testApplication {
            val inbox = InMemoryInboxStore()
            start(RateLimitPolicies(sendPerAddress = tight(1)), inbox = inbox)

            assertEquals(HttpStatusCode.Created, client.send().status)
            assertEquals(HttpStatusCode.TooManyRequests, client.send().status)

            assertEquals(1, inbox.messagesAfter(id, 0).size)
        }

    @Test
    fun `the health endpoint is never limited`() =
        testApplication {
            start(RateLimitPolicies(otherPerAddress = tight(1)))

            repeat(5) { assertEquals(HttpStatusCode.OK, client.get("/health").status) }
        }

    @Test
    fun `scopes are limited separately`() =
        testApplication {
            start(RateLimitPolicies(challengePerAddress = tight(1)))
            repeat(3) { client.challenge() }

            val login =
                client.post(ProtocolRoutes.AUTH_TOKEN) {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            assertEquals(HttpStatusCode.BadRequest, login.status)
        }

    @Test
    fun `forwarded headers are ignored unless trusted`() =
        testApplication {
            start(RateLimitPolicies(challengePerAddress = tight(1)))

            assertEquals(HttpStatusCode.OK, client.challenge("1.1.1.1").status)
            assertEquals(HttpStatusCode.TooManyRequests, client.challenge("2.2.2.2").status)
        }

    @Test
    fun `a trusted proxy header separates clients by its last entry`() =
        testApplication {
            start(RateLimitPolicies(challengePerAddress = tight(1)), trustProxy = true)

            assertEquals(HttpStatusCode.OK, client.challenge("1.1.1.1").status)
            assertEquals(HttpStatusCode.OK, client.challenge("2.2.2.2").status)
            assertEquals(HttpStatusCode.TooManyRequests, client.challenge("9.9.9.9, 1.1.1.1").status)
        }

    @Test
    fun `unauthorised sends do not use up the recipient limit`() =
        testApplication {
            start(RateLimitPolicies(sendPerMailbox = tight(1)))

            repeat(5) {
                assertEquals(HttpStatusCode.Unauthorized, client.send(Base64Url.encode(ByteArray(16) { 4 })).status)
            }

            assertEquals(HttpStatusCode.Created, client.send().status)
        }

    @Test
    fun `bundle fetches are limited per recipient with a retry hint`() =
        testApplication {
            start(RateLimitPolicies(bundlePerMailbox = RateLimitPolicy(burst = 2, refillPerSecond = 0.1)))

            assertEquals(HttpStatusCode.OK, client.bundle().status)
            assertEquals(HttpStatusCode.OK, client.bundle().status)
            val limited = client.bundle()

            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("10", limited.headers[HttpHeaders.RetryAfter])
        }
}
