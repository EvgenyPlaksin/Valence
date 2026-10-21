package com.lnight.valence.protocol

import com.lnight.valence.protocol.http.AccessTokenResponse
import com.lnight.valence.protocol.http.AuthChallengeResponse
import com.lnight.valence.protocol.http.AuthTokenRequest
import com.lnight.valence.protocol.http.RegisterRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthContractTest {
    @Test
    fun `auth challenge response matches its fixture`() {
        val response = ContractAssertions.roundTrip("auth-challenge-response", AuthChallengeResponse.serializer())

        assertEquals(1_760_000_060_000L, response.expiresAt)
    }

    @Test
    fun `register request matches its fixture`() {
        val request = ContractAssertions.roundTrip("register-request", RegisterRequest.serializer())

        assertEquals("mbx_g4r2k7x5qmwv6bnzdcs3n4pe5a", request.mailboxId)
        assertEquals(listOf(1, 2), request.oneTimePreKeys.map { it.id })
    }

    @Test
    fun `access token response matches its fixture`() {
        val response = ContractAssertions.roundTrip("access-token-response", AccessTokenResponse.serializer())

        assertEquals(1_760_003_601_000L, response.expiresAt)
    }

    @Test
    fun `auth token request matches its fixture`() {
        val request = ContractAssertions.roundTrip("auth-token-request", AuthTokenRequest.serializer())

        assertEquals("mbx_g4r2k7x5qmwv6bnzdcs3n4pe5a", request.mailboxId)
    }
}
