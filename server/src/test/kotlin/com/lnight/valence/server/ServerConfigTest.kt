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
}
