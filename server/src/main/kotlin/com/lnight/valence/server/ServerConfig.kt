package com.lnight.valence.server

import com.lnight.valence.protocol.Base64Url

object ServerConfig {
    const val CHALLENGE_KEY_VARIABLE = "VALENCE_CHALLENGE_KEY"

    fun challengeKey(environment: Map<String, String> = System.getenv()): ByteArray {
        val encoded = checkNotNull(environment[CHALLENGE_KEY_VARIABLE]) { "$CHALLENGE_KEY_VARIABLE is not set" }
        return Base64Url.decode(encoded)
    }
}
