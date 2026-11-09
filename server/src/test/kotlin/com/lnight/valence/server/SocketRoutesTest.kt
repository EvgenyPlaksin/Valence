package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.GoldenFixtures
import com.lnight.valence.protocol.ProtocolHeaders
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.frame.DeliveryConfirmFrame
import com.lnight.valence.protocol.frame.MessageDeliverFrame
import com.lnight.valence.protocol.frame.SyncResumeFrame
import com.lnight.valence.server.domain.NewMessage
import com.lnight.valence.server.store.InMemoryInboxStore
import com.lnight.valence.server.store.InMemoryMailboxStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SocketRoutesTest {
    private val id = RegistrationTestSupport.MAILBOX_ID
    private val token = FakeAccessTokens.bearer(id)
    private val now = ChallengeTestSupport.ISSUED_AT_MILLIS

    private fun ApplicationTestBuilder.start(messages: Int = 0): InMemoryInboxStore {
        val inbox = InMemoryInboxStore()
        (1..messages).forEach { inbox.append(id, NewMessage(byteArrayOf(it.toByte()), now, now + 60_000L), 1_000) }
        val store = InMemoryMailboxStore().also { it.create(DeliveryKeyTestSupport.mailbox(), emptyList()) }
        application { module(TestServices.create(store = store, inbox = inbox)) }
        return inbox
    }

    private fun ApplicationTestBuilder.socketClient(): HttpClient = createClient { install(WebSockets) }

    private suspend fun HttpClient.socket(block: suspend DefaultClientWebSocketSession.() -> Unit) =
        webSocket("/v1/socket", request = { bearerAuth(token) }, block = block)

    private suspend fun DefaultClientWebSocketSession.sendResume(after: Long) =
        send(Frame.Text(ProtocolJson.encodeClientFrame(SyncResumeFrame(afterServerSeq = after))))

    private suspend fun DefaultClientWebSocketSession.sendConfirm(upTo: Long) =
        send(Frame.Text(ProtocolJson.encodeClientFrame(DeliveryConfirmFrame(upToServerSeq = upTo))))

    private suspend fun DefaultClientWebSocketSession.receiveText(): String =
        withTimeout(5.seconds) { (incoming.receive() as Frame.Text).readText() }

    private suspend fun DefaultClientWebSocketSession.receiveDelivery(): MessageDeliverFrame =
        assertIs<MessageDeliverFrame>(ProtocolJson.decodeServerFrame(receiveText()))

    private suspend fun DefaultClientWebSocketSession.assertInvalidFrame() {
        assertEquals(
            ProtocolJson.json.parseToJsonElement(GoldenFixtures.read("error-invalid-frame")),
            ProtocolJson.json.parseToJsonElement(receiveText()),
        )
    }

    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(5.seconds) { while (!condition()) delay(10) }

    private suspend fun HttpClient.sendMessage(envelope: String) =
        post("/v1/messages/$id") {
            header(ProtocolHeaders.DELIVERY_KEY, Base64Url.encode(DeliveryKeyTestSupport.deliveryKey))
            contentType(ContentType.Application.Json)
            setBody("""{"v":1,"envelope":"$envelope"}""")
        }

    @Test
    fun `the socket requires a valid token`() =
        testApplication {
            start()
            val client = socketClient()

            assertFails { client.webSocketSession("/v1/socket") }
            assertFails { client.webSocketSession("/v1/socket") { bearerAuth("garbage") } }
        }

    @Test
    fun `nothing is delivered before the client resumes`() =
        testApplication {
            start(messages = 2)

            socketClient().socket {
                assertNull(withTimeoutOrNull(300.milliseconds) { incoming.receive() })
            }
        }

    @Test
    fun `resuming replays the stored messages after the cursor in order`() =
        testApplication {
            start(messages = 3)

            socketClient().socket {
                sendResume(after = 1)

                val first = receiveDelivery()
                val second = receiveDelivery()
                assertEquals(listOf(2L, 3L), listOf(first.serverSeq, second.serverSeq))
                assertContentEquals(byteArrayOf(2), Base64Url.decode(first.envelope))
                assertEquals(now, first.serverTs)
            }
        }

    @Test
    fun `a message sent while connected is pushed after the replay`() =
        testApplication {
            start(messages = 1)
            val client = socketClient()

            client.socket {
                sendResume(after = 0)
                assertEquals(1L, receiveDelivery().serverSeq)

                client.sendMessage("u8F2Q0xWm3sA")

                val pushed = receiveDelivery()
                assertEquals(2L, pushed.serverSeq)
                assertContentEquals(Base64Url.decode("u8F2Q0xWm3sA"), Base64Url.decode(pushed.envelope))
            }
        }

    @Test
    fun `a message sent before the resume is still delivered`() =
        testApplication {
            start()
            val client = socketClient()

            client.socket {
                client.sendMessage("u8F2Q0xWm3sA")
                sendResume(after = 0)

                assertEquals(1L, receiveDelivery().serverSeq)
            }
        }

    @Test
    fun `a confirm deletes the confirmed messages`() =
        testApplication {
            val inbox = start(messages = 3)

            socketClient().socket {
                sendResume(after = 0)
                repeat(3) { receiveDelivery() }

                sendConfirm(upTo = 2)

                awaitUntil { inbox.messagesAfter(id, 0).map { it.serverSeq } == listOf(3L) }
            }
        }

    @Test
    fun `expired messages are never delivered`() =
        testApplication {
            val inbox = start()
            inbox.append(id, NewMessage(byteArrayOf(1), now - 10_000L, now), 1_000)
            inbox.append(id, NewMessage(byteArrayOf(2), now, now + 60_000L), 1_000)

            socketClient().socket {
                sendResume(after = 0)

                assertEquals(2L, receiveDelivery().serverSeq)
            }
        }

    @Test
    fun `invalid frames are answered with an error and the connection stays open`() =
        testApplication {
            start(messages = 1)

            socketClient().socket {
                send(Frame.Text("not json"))
                assertInvalidFrame()
                send(Frame.Text("""{"type":"nope","v":1}"""))
                assertInvalidFrame()
                send(Frame.Text("""{"type":"sync.resume","v":99,"afterServerSeq":0}"""))
                assertInvalidFrame()
                send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
                assertInvalidFrame()
                sendConfirm(upTo = 1)
                assertInvalidFrame()

                sendResume(after = 0)
                assertEquals(1L, receiveDelivery().serverSeq)
            }
        }

    @Test
    fun `a second resume is an invalid frame`() =
        testApplication {
            start(messages = 1)

            socketClient().socket {
                sendResume(after = 0)
                receiveDelivery()

                sendResume(after = 0)

                assertInvalidFrame()
            }
        }

    @Test
    fun `a frame over 4 KiB closes the connection`() =
        testApplication {
            start()

            socketClient().socket {
                send(Frame.Text("x".repeat(5_000)))

                val reason = withTimeout(5.seconds) { closeReason.await() }
                assertEquals(CloseReason.Codes.TOO_BIG.code, reason?.code)
            }
        }

    @Test
    fun `a newer connection closes the older one with code 4000`() =
        testApplication {
            start(messages = 1)
            val client = socketClient()
            val first = client.webSocketSession("/v1/socket") { bearerAuth(token) }
            first.sendResume(after = 0)
            first.receiveDelivery()

            val second = client.webSocketSession("/v1/socket") { bearerAuth(token) }
            val reason = withTimeout(5.seconds) { first.closeReason.await() }

            assertEquals(4000.toShort(), reason?.code)
            second.sendResume(after = 0)
            assertEquals(1L, second.receiveDelivery().serverSeq)
            second.close()
        }
}
