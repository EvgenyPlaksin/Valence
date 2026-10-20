package com.lnight.valence.protocol

import com.lnight.valence.protocol.http.PreKeyBundleResponse
import com.lnight.valence.protocol.http.SendMessageRequest
import com.lnight.valence.protocol.http.SendMessageResponse
import com.lnight.valence.protocol.http.SenderCertificateResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HttpContractTest {
    @Test
    fun `send message request matches its fixture`() {
        val request = ContractAssertions.roundTrip("send-message-request", SendMessageRequest.serializer())

        assertEquals("u8F2Q0xWm3sA", request.envelope)
        assertEquals(2_592_000L, request.ttlSeconds)
    }

    @Test
    fun `send message response matches its fixture`() {
        val response = ContractAssertions.roundTrip("send-message-response", SendMessageResponse.serializer())

        assertEquals(1_760_000_000_123L, response.serverTs)
    }

    @Test
    fun `prekey bundle with a one time prekey matches its fixture`() {
        val bundle = ContractAssertions.roundTrip("prekey-bundle", PreKeyBundleResponse.serializer())

        assertEquals(48, assertNotNull(bundle.oneTimePreKey).id)
    }

    @Test
    fun `prekey bundle without a one time prekey matches its fixture`() {
        val bundle = ContractAssertions.roundTrip("prekey-bundle-exhausted", PreKeyBundleResponse.serializer())

        assertNull(bundle.oneTimePreKey)
    }

    @Test
    fun `sender certificate matches its fixture`() {
        val certificate = ContractAssertions.roundTrip("sender-certificate", SenderCertificateResponse.serializer())

        assertEquals(1_760_086_400_000L, certificate.expiresAt)
    }
}
