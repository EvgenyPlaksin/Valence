package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url

object ServerConfig {
    const val CHALLENGE_KEY_VARIABLE = "VALENCE_CHALLENGE_KEY"
    const val TOKEN_KEY_VARIABLE = "VALENCE_TOKEN_KEY"

    fun challengeKey(environment: Map<String, String> = System.getenv()): ByteArray =
        decode(environment, CHALLENGE_KEY_VARIABLE)

    fun tokenKey(environment: Map<String, String> = System.getenv()): ByteArray {
        val key = decode(environment, TOKEN_KEY_VARIABLE)
        check(!key.contentEquals(challengeKey(environment))) {
            "$TOKEN_KEY_VARIABLE must differ from $CHALLENGE_KEY_VARIABLE"
        }
        return key
    }

    private fun decode(
        environment: Map<String, String>,
        variable: String,
    ): ByteArray {
        val encoded = checkNotNull(environment[variable]) { "$variable is not set" }
        return Base64Url.decode(encoded)
    }
}
