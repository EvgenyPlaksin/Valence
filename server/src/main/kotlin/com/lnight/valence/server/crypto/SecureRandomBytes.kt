package com.lnight.valence.server.crypto

import com.lnight.valence.server.domain.RandomBytes
import java.security.SecureRandom

class SecureRandomBytes(
    private val random: SecureRandom = SecureRandom(),
) : RandomBytes {
    override fun next(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
}
