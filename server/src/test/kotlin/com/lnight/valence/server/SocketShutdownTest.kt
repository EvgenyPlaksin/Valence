package com.lnight.valence.server

import com.lnight.valence.server.store.InMemoryInboxStore
import com.lnight.valence.server.store.InMemoryMailboxStore
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SocketShutdownTest {
    private val token = FakeAccessTokens.bearer(RegistrationTestSupport.MAILBOX_ID)
    private val metricsToken = "shutdown-metrics-token-123456"

    private fun ApplicationTestBuilder.start(
        drainMillis: Long = 0,
        inbox: InMemoryInboxStore = InMemoryInboxStore(),
    ): () -> Unit {
        val store = InMemoryMailboxStore().seed(DeliveryKeyTestSupport.mailbox())
        var stop: () -> Unit = {}
        application {
            module(TestServices.create(store = store, inbox = inbox, metricsToken = metricsToken), drainMillis)
            stop = { monitor.raise(ApplicationStopPreparing, environment) }
        }
        return { stop() }
    }

    @Test
    fun `stopping the application closes open sockets with code 1001`() =
        testApplication {
            val stop = start()
            val client = createClient { install(WebSockets) }
            val session = client.webSocketSession("/v1/socket") { bearerAuth(token) }

            stop()

            val reason = withTimeout(5.seconds) { session.closeReason.await() }
            assertEquals(CloseReason.Codes.GOING_AWAY.code, reason?.code)
        }

    @Test
    fun `a socket opened while the application is stopping is closed with code 1001`() =
        testApplication {
            val stop = start()
            val client = createClient { install(WebSockets) }
            client.get("/health")

            stop()
            val session = client.webSocketSession("/v1/socket") { bearerAuth(token) }

            val reason = withTimeout(5.seconds) { session.closeReason.await() }
            assertEquals(CloseReason.Codes.GOING_AWAY.code, reason?.code)
        }

    @Test
    fun `readiness fails and new sockets are refused during the drain window`() =
        testApplication {
            val inbox = InMemoryInboxStore()
            val stop = start(drainMillis = 2_000, inbox = inbox)
            val client = createClient { install(WebSockets) }
            val existingSession = client.webSocketSession("/v1/socket") { bearerAuth(token) }
            val generationBeforeStop = inbox.currentSocketGeneration(RegistrationTestSupport.MAILBOX_ID)
            val stopFuture = CompletableFuture.runAsync { stop() }

            val readyResponse =
                withTimeout(5.seconds) {
                    var response = client.get("/ready")
                    while (response.status.value != 503) {
                        delay(10)
                        response = client.get("/ready")
                    }
                    response
                }
            assertEquals(503, readyResponse.status.value)
            assertEquals(200, client.get("/health").status.value)
            assertFalse(existingSession.closeReason.isCompleted)

            val rejectedSession = client.webSocketSession("/v1/socket") { bearerAuth(token) }
            val rejectedReason = withTimeout(5.seconds) { rejectedSession.closeReason.await() }
            assertEquals(CloseReason.Codes.GOING_AWAY.code, rejectedReason?.code)
            assertEquals(generationBeforeStop, inbox.currentSocketGeneration(RegistrationTestSupport.MAILBOX_ID))
            assertFalse(existingSession.closeReason.isCompleted)

            val metrics =
                client.get("/metrics") { bearerAuth(metricsToken) }.bodyAsText()
            assertTrue(metrics.contains("""valence_http_responses_total{scope="health",status="503"}"""))
            assertTrue(metrics.contains("""valence_http_responses_total{scope="health",status="200"}"""))

            stopFuture.get(5, TimeUnit.SECONDS)
            val existingReason = withTimeout(5.seconds) { existingSession.closeReason.await() }
            assertEquals(CloseReason.Codes.GOING_AWAY.code, existingReason?.code)
        }
}
