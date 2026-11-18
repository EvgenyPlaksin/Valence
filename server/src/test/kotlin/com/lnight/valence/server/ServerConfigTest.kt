package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class ServerConfigTest {
    @Test
    fun `challenge key is decoded from the environment`() {
        val key = ByteArray(32) { it.toByte() }
        val environment = mapOf(ServerConfig.CHALLENGE_KEY_VARIABLE to Base64Url.encode(key))

        assertContentEquals(key, ServerConfig.challengeKey(environment))
    }

    @Test
    fun `a missing challenge key fails fast`() {
        assertFailsWith<IllegalStateException> { ServerConfig.challengeKey(emptyMap()) }
    }

    @Test
    fun `token key is decoded from the environment`() {
        val challengeKey = ByteArray(32) { it.toByte() }
        val tokenKey = ByteArray(32) { (it + 1).toByte() }
        val environment =
            mapOf(
                ServerConfig.CHALLENGE_KEY_VARIABLE to Base64Url.encode(challengeKey),
                ServerConfig.TOKEN_KEY_VARIABLE to Base64Url.encode(tokenKey),
            )

        assertContentEquals(tokenKey, ServerConfig.tokenKey(environment))
    }

    @Test
    fun `identical challenge and token keys are rejected`() {
        val key = Base64Url.encode(ByteArray(32) { it.toByte() })
        val environment =
            mapOf(ServerConfig.CHALLENGE_KEY_VARIABLE to key, ServerConfig.TOKEN_KEY_VARIABLE to key)

        assertFailsWith<IllegalStateException> { ServerConfig.tokenKey(environment) }
    }

    @Test
    fun `the certificate configuration is decoded from the environment`() {
        val certificate = ByteArray(40) { it.toByte() }
        val key = ByteArray(32) { (it + 5).toByte() }
        val environment =
            mapOf(
                ServerConfig.SERVER_CERTIFICATE_VARIABLE to Base64Url.encode(certificate),
                ServerConfig.CERTIFICATE_KEY_VARIABLE to Base64Url.encode(key),
            )

        assertContentEquals(certificate, ServerConfig.serverCertificate(environment))
        assertContentEquals(key, ServerConfig.certificateKey(environment))
    }

    @Test
    fun `missing certificate configuration fails fast`() {
        assertFailsWith<IllegalStateException> { ServerConfig.serverCertificate(emptyMap()) }
        assertFailsWith<IllegalStateException> { ServerConfig.certificateKey(emptyMap()) }
    }
}
