package com.lnight.valence.server

import com.lnight.valence.server.store.InMemoryMailboxStore
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class SocketShutdownTest {
    private val token = FakeAccessTokens.bearer(RegistrationTestSupport.MAILBOX_ID)

    private fun ApplicationTestBuilder.start(): () -> Unit {
        val store = InMemoryMailboxStore().also { it.create(DeliveryKeyTestSupport.mailbox(), emptyList()) }
        var stop: () -> Unit = {}
        application {
            module(TestServices.create(store = store))
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
}
