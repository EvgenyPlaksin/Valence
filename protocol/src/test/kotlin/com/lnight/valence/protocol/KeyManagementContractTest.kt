package com.lnight.valence.protocol

import com.lnight.valence.protocol.http.KeyStatusResponse
import com.lnight.valence.protocol.http.ReplaceSignedPreKeysRequest
import com.lnight.valence.protocol.http.UploadOneTimeKyberPreKeysRequest
import com.lnight.valence.protocol.http.UploadOneTimePreKeysRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyManagementContractTest {
    @Test
    fun `key status response matches its fixture`() {
        val response = ContractAssertions.roundTrip("key-status-response", KeyStatusResponse.serializer())

        assertEquals(17, response.oneTimeRemaining)
        assertEquals(0, response.oneTimeKyberRemaining)
    }

    @Test
    fun `replace signed prekeys request matches its fixture`() {
        val request =
            ContractAssertions.roundTrip("replace-signed-prekeys-request", ReplaceSignedPreKeysRequest.serializer())

        assertEquals(2, request.signedPreKey.id)
        assertEquals(2, request.kyberPreKey.id)
    }

    @Test
    fun `upload one time prekeys request matches its fixture`() {
        val request =
            ContractAssertions.roundTrip("upload-one-time-prekeys-request", UploadOneTimePreKeysRequest.serializer())

        assertEquals(listOf(101, 102), request.oneTimePreKeys.map { it.id })
    }

    @Test
    fun `upload one time kyber prekeys request matches its fixture`() {
        val request =
            ContractAssertions.roundTrip(
                "upload-one-time-kyber-prekeys-request",
                UploadOneTimeKyberPreKeysRequest.serializer(),
            )

        assertEquals(listOf(101, 102), request.oneTimeKyberPreKeys.map { it.id })
    }

    @Test
    fun `zero kyber count is serialized in the key status fixture`() {
        val response = KeyStatusResponse(oneTimeRemaining = 0)

        assertEquals(
            ProtocolJson.json.parseToJsonElement(GoldenFixtures.read("key-status-response-zero")),
            ProtocolJson.json.encodeToJsonElement(KeyStatusResponse.serializer(), response),
        )
    }
}
