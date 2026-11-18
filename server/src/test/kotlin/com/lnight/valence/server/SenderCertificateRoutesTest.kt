package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.GoldenFixtures
import com.lnight.valence.protocol.ProtocolJson
import com.lnight.valence.protocol.ProtocolRoutes
import com.lnight.valence.server.store.InMemoryMailboxStore
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SenderCertificateRoutesTest {
    private val id = RegistrationTestSupport.MAILBOX_ID

    private fun ApplicationTestBuilder.start() {
        val store = InMemoryMailboxStore().also { it.create(RegistrationTestSupport.mailbox(), emptyList()) }
        application { module(TestServices.create(store = store)) }
    }

    @Test
    fun `the certificate endpoint requires a valid token`() =
        testApplication {
            start()

            assertEquals(HttpStatusCode.Unauthorized, client.get(ProtocolRoutes.SENDER_CERTIFICATE).status)
            val garbage = client.get(ProtocolRoutes.SENDER_CERTIFICATE) { bearerAuth("garbage") }
            assertEquals(HttpStatusCode.Unauthorized, garbage.status)
        }

    @Test
    fun `a certificate is answered like the protocol fixture`() =
        testApplication {
            start()

            val response = client.get(ProtocolRoutes.SENDER_CERTIFICATE) { bearerAuth(FakeAccessTokens.bearer(id)) }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                ProtocolJson.json.parseToJsonElement(GoldenFixtures.read("sender-certificate")),
                ProtocolJson.json.parseToJsonElement(response.bodyAsText()),
            )
            assertContentEquals(FakeCertificates.bytes, Base64Url.decode("c3VwZXJjZXJ0"))
        }

    @Test
    fun `a token for an unknown mailbox answers 401`() =
        testApplication {
            start()

            val token = FakeAccessTokens.bearer("mbx_bbbbbbbbbbbbbbbbbbbbbbbbbe")
            val response = client.get(ProtocolRoutes.SENDER_CERTIFICATE) { bearerAuth(token) }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
