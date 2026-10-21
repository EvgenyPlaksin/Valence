package com.lnight.valence.protocol

import com.lnight.valence.protocol.http.KeyStatusResponse
import com.lnight.valence.protocol.http.ReplaceSignedPreKeysRequest
import com.lnight.valence.protocol.http.UploadOneTimePreKeysRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyManagementContractTest {
    @Test
    fun `key status response matches its fixture`() {
        val response = ContractAssertions.roundTrip("key-status-response", KeyStatusResponse.serializer())

        assertEquals(17, response.oneTimeRemaining)
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
}
