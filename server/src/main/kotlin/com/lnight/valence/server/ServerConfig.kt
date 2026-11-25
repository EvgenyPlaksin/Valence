package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url
import java.net.URI

object ServerConfig {
    const val CHALLENGE_KEY_VARIABLE = "VALENCE_CHALLENGE_KEY"
    const val TOKEN_KEY_VARIABLE = "VALENCE_TOKEN_KEY"
    const val SERVER_CERTIFICATE_VARIABLE = "VALENCE_SERVER_CERTIFICATE"
    const val CERTIFICATE_KEY_VARIABLE = "VALENCE_CERTIFICATE_KEY"
    const val DATABASE_URL_VARIABLE = "VALENCE_DATABASE_URL"
    const val DATABASE_USER_VARIABLE = "VALENCE_DATABASE_USER"
    const val DATABASE_PASSWORD_VARIABLE = "VALENCE_DATABASE_PASSWORD"
    const val DATABASE_MAX_CONNECTIONS_VARIABLE = "VALENCE_DB_MAX_CONNECTIONS"
    const val DATABASE_AUTO_MIGRATE_VARIABLE = "VALENCE_DB_AUTO_MIGRATE"
    const val REDIS_URL_VARIABLE = "VALENCE_REDIS_URL"
    const val REDIS_CHANNEL_PREFIX_VARIABLE = "VALENCE_REDIS_CHANNEL_PREFIX"
    const val TRUST_PROXY_HEADERS_VARIABLE = "VALENCE_TRUST_PROXY_HEADERS"
    const val METRICS_TOKEN_VARIABLE = "VALENCE_METRICS_TOKEN"
    internal const val SHUTDOWN_DRAIN_MS_VARIABLE = "VALENCE_SHUTDOWN_DRAIN_MS"
    private const val MINIMUM_METRICS_TOKEN_LENGTH = 16
    private const val DEFAULT_DATABASE_MAX_CONNECTIONS = 10
    internal const val MAX_SHUTDOWN_DRAIN_MILLIS = 60_000L

    internal fun shutdownDrainMillis(environment: Map<String, String> = System.getenv()): Long {
        val value = environment[SHUTDOWN_DRAIN_MS_VARIABLE] ?: return 0L
        val millis =
            value.toLongOrNull()
                ?: error("$SHUTDOWN_DRAIN_MS_VARIABLE must be an integer between 0 and $MAX_SHUTDOWN_DRAIN_MILLIS")
        check(millis in 0..MAX_SHUTDOWN_DRAIN_MILLIS) {
            "$SHUTDOWN_DRAIN_MS_VARIABLE must be between 0 and $MAX_SHUTDOWN_DRAIN_MILLIS"
        }
        return millis
    }

    fun trustProxyHeaders(environment: Map<String, String> = System.getenv()): Boolean =
        environment[TRUST_PROXY_HEADERS_VARIABLE] == "true"

    fun metricsToken(environment: Map<String, String> = System.getenv()): String? =
        environment[METRICS_TOKEN_VARIABLE]?.takeIf { it.isNotBlank() }?.also {
            check(it.length >= MINIMUM_METRICS_TOKEN_LENGTH) {
                "$METRICS_TOKEN_VARIABLE needs at least $MINIMUM_METRICS_TOKEN_LENGTH characters"
            }
        }

    fun challengeKey(environment: Map<String, String> = System.getenv()): ByteArray =
        decode(environment, CHALLENGE_KEY_VARIABLE)

    fun tokenKey(environment: Map<String, String> = System.getenv()): ByteArray {
        val key = decode(environment, TOKEN_KEY_VARIABLE)
        check(!key.contentEquals(challengeKey(environment))) {
            "$TOKEN_KEY_VARIABLE must differ from $CHALLENGE_KEY_VARIABLE"
        }
        return key
    }

    fun serverCertificate(environment: Map<String, String> = System.getenv()): ByteArray =
        decode(environment, SERVER_CERTIFICATE_VARIABLE)

    fun certificateKey(environment: Map<String, String> = System.getenv()): ByteArray =
        decode(environment, CERTIFICATE_KEY_VARIABLE)

    fun databaseConfiguration(environment: Map<String, String> = System.getenv()): DatabaseConfiguration {
        val url = requiredValue(environment, DATABASE_URL_VARIABLE)
        check(url.startsWith("jdbc:postgresql://") && !url.any(Char::isWhitespace)) {
            "$DATABASE_URL_VARIABLE must be a PostgreSQL JDBC URL"
        }

        check(!DATABASE_CREDENTIAL_PARAMETER.containsMatchIn(url)) {
            "$DATABASE_URL_VARIABLE must not contain database credentials"
        }
        val maxConnections =
            environment[DATABASE_MAX_CONNECTIONS_VARIABLE]
                ?.toIntOrNull()
                ?: if (DATABASE_MAX_CONNECTIONS_VARIABLE in environment) {
                    error("$DATABASE_MAX_CONNECTIONS_VARIABLE must be a positive integer")
                } else {
                    DEFAULT_DATABASE_MAX_CONNECTIONS
                }
        check(maxConnections > 0) { "$DATABASE_MAX_CONNECTIONS_VARIABLE must be a positive integer" }
        val autoMigrate =
            environment[DATABASE_AUTO_MIGRATE_VARIABLE]?.let { value ->
                when (value.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> error("$DATABASE_AUTO_MIGRATE_VARIABLE must be true or false")
                }
            } ?: true
        return DatabaseConfiguration(
            url = url,
            username = requiredValue(environment, DATABASE_USER_VARIABLE),
            password = requiredValue(environment, DATABASE_PASSWORD_VARIABLE),
            maxConnections = maxConnections,
            autoMigrate = autoMigrate,
        )
    }

    fun redisConfiguration(environment: Map<String, String> = System.getenv()): RedisConfiguration? {
        val url = environment[REDIS_URL_VARIABLE]?.takeIf { it.isNotBlank() } ?: return null
        val uri =
            runCatching { URI(url) }.getOrElse {
                error("$REDIS_URL_VARIABLE must be a valid Redis URL")
            }
        check(uri.scheme in setOf("redis", "rediss") && !uri.host.isNullOrBlank()) {
            "$REDIS_URL_VARIABLE must use redis:// or rediss:// and include a host"
        }
        check(!url.any(Char::isWhitespace)) { "$REDIS_URL_VARIABLE must not contain whitespace" }
        val prefix =
            checkNotNull(environment[REDIS_CHANNEL_PREFIX_VARIABLE]?.takeIf { it.isNotBlank() }) {
                "$REDIS_CHANNEL_PREFIX_VARIABLE is required when Redis is enabled"
            }
        check(prefix.matches(Regex("[A-Za-z0-9:_-]+"))) {
            "$REDIS_CHANNEL_PREFIX_VARIABLE may contain only letters, numbers, :, _ and -"
        }
        return RedisConfiguration(url, "$prefix:notifications")
    }

    private fun decode(
        environment: Map<String, String>,
        variable: String,
    ): ByteArray {
        val encoded = checkNotNull(environment[variable]) { "$variable is not set" }
        return Base64Url.decode(encoded)
    }

    private fun requiredValue(
        environment: Map<String, String>,
        variable: String,
    ): String = checkNotNull(environment[variable]?.takeIf { it.isNotBlank() }) { "$variable is not set" }

    private val DATABASE_CREDENTIAL_PARAMETER = Regex("(?i)[?&](?:user|password)=")
}
