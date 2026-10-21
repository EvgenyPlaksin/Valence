package com.lnight.valence.server.crypto

import com.lnight.valence.server.domain.ChallengeAuthenticator
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HmacChallengeAuthenticator(
    key: ByteArray,
) : ChallengeAuthenticator {
    private val keySpec: SecretKeySpec

    init {
        require(key.size >= MINIMUM_KEY_SIZE) { "The challenge key needs at least $MINIMUM_KEY_SIZE bytes" }
        keySpec = SecretKeySpec(key, ALGORITHM)
    }

    override fun sign(data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(data)
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val MINIMUM_KEY_SIZE = 32
    }
}
