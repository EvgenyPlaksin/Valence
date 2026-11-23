package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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

    @Test
    fun `database configuration requires a jdbc url and separate credentials`() {
        val configuration =
            ServerConfig.databaseConfiguration(
                mapOf(
                    ServerConfig.DATABASE_URL_VARIABLE to "jdbc:postgresql://localhost:5432/valence",
                    ServerConfig.DATABASE_USER_VARIABLE to "valence",
                    ServerConfig.DATABASE_PASSWORD_VARIABLE to "local-secret",
                ),
            )

        assertEquals("jdbc:postgresql://localhost:5432/valence", configuration.url)
        assertEquals("valence", configuration.username)
        assertEquals("local-secret", configuration.password)
        assertEquals(10, configuration.maxConnections)
        assertEquals(true, configuration.autoMigrate)
    }

    @Test
    fun `database connection and migration settings are validated`() {
        val base =
            mapOf(
                ServerConfig.DATABASE_URL_VARIABLE to "jdbc:postgresql://localhost:5432/valence",
                ServerConfig.DATABASE_USER_VARIABLE to "valence",
                ServerConfig.DATABASE_PASSWORD_VARIABLE to "local-secret",
            )

        assertFailsWith<IllegalStateException> { ServerConfig.databaseConfiguration(emptyMap()) }
        assertFailsWith<IllegalStateException> {
            ServerConfig.databaseConfiguration(base + (ServerConfig.DATABASE_URL_VARIABLE to "jdbc:h2:mem:test"))
        }
        assertFailsWith<IllegalStateException> {
            ServerConfig.databaseConfiguration(
                base + (ServerConfig.DATABASE_URL_VARIABLE to "jdbc:postgresql://host/db?password=secret"),
            )
        }
        assertFailsWith<IllegalStateException> {
            ServerConfig.databaseConfiguration(base + (ServerConfig.DATABASE_MAX_CONNECTIONS_VARIABLE to "0"))
        }
        assertFailsWith<IllegalStateException> {
            ServerConfig.databaseConfiguration(base + (ServerConfig.DATABASE_AUTO_MIGRATE_VARIABLE to "sometimes"))
        }

        val configuration =
            ServerConfig.databaseConfiguration(
                base +
                    (ServerConfig.DATABASE_MAX_CONNECTIONS_VARIABLE to "4") +
                    (ServerConfig.DATABASE_AUTO_MIGRATE_VARIABLE to "false"),
            )
        assertEquals(4, configuration.maxConnections)
        assertEquals(false, configuration.autoMigrate)
    }

    @Test
    fun `proxy headers are trusted only when explicitly enabled`() {
        assertEquals(false, ServerConfig.trustProxyHeaders(emptyMap()))
        assertEquals(false, ServerConfig.trustProxyHeaders(mapOf(ServerConfig.TRUST_PROXY_HEADERS_VARIABLE to "yes")))
        assertEquals(true, ServerConfig.trustProxyHeaders(mapOf(ServerConfig.TRUST_PROXY_HEADERS_VARIABLE to "true")))
    }

    @Test
    fun `the metrics token is optional and must be long enough`() {
        assertNull(ServerConfig.metricsToken(emptyMap()))
        assertNull(ServerConfig.metricsToken(mapOf(ServerConfig.METRICS_TOKEN_VARIABLE to " ")))
        assertEquals(
            "a-long-enough-token",
            ServerConfig.metricsToken(mapOf(ServerConfig.METRICS_TOKEN_VARIABLE to "a-long-enough-token")),
        )
        assertFailsWith<IllegalStateException> {
            ServerConfig.metricsToken(mapOf(ServerConfig.METRICS_TOKEN_VARIABLE to "short"))
        }
    }
}
