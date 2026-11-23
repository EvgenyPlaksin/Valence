package com.lnight.valence.server

import com.lnight.valence.server.domain.SenderCertificateIssuer
import com.lnight.valence.server.domain.SenderCertificateService
import com.lnight.valence.server.store.InMemoryMailboxStore
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SenderCertificateServiceTest {
    private class Request(
        val mailboxId: String,
        val identityKey: ByteArray,
        val expiresAtMillis: Long,
    )

    private val now = ChallengeTestSupport.ISSUED_AT_MILLIS
    private val requests = mutableListOf<Request>()
    private val store = InMemoryMailboxStore()
    private val service =
        SenderCertificateService(
            store,
            SenderCertificateIssuer { id, key, expiresAt ->
                requests += Request(id, key, expiresAt)
                byteArrayOf(1, 2, 3)
            },
            Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
        )

    @Test
    fun `a certificate is issued for the stored identity key and lasts 24 hours`() =
        runTest {
            store.create(RegistrationTestSupport.mailbox(), emptyList())
            val issued = service.issue(RegistrationTestSupport.MAILBOX_ID)

            assertContentEquals(byteArrayOf(1, 2, 3), issued?.bytes)
            assertEquals(now + 86_400_000L, issued?.expiresAtMillis)
            val request = requests.single()
            assertEquals(RegistrationTestSupport.MAILBOX_ID, request.mailboxId)
            assertContentEquals(ByteArray(33) { 4 }, request.identityKey)
            assertEquals(now + 86_400_000L, request.expiresAtMillis)
        }

    @Test
    fun `an unknown mailbox gets nothing`() =
        runTest {
            assertNull(service.issue("mbx_aaaaaaaaaaaaaaaaaaaaaaaaaa"))
            assertTrue(requests.isEmpty())
        }
}
