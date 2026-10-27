package com.lnight.valence.server.crypto

import com.lnight.valence.server.domain.AccessToken
import com.lnight.valence.server.domain.AccessTokenIssuer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class JwtAccessTokenIssuer(
    private val clock: Clock,
    key: ByteArray,
    private val lifetime: Duration = DEFAULT_LIFETIME,
) : AccessTokenIssuer {
    private val keySpec: SecretKeySpec

    init {
        require(key.size >= MINIMUM_KEY_SIZE) { "The token key needs at least $MINIMUM_KEY_SIZE bytes" }
        keySpec = SecretKeySpec(key, ALGORITHM)
    }

    override fun issue(mailboxId: String): AccessToken {
        val issuedAt = clock.instant()
        val expiresAt = issuedAt.plus(lifetime)
        val claims =
            buildJsonObject {
                put("sub", mailboxId)
                put("iat", issuedAt.epochSecond)
                put("exp", expiresAt.epochSecond)
            }
        val signingInput = "$ENCODED_HEADER.${encode(claims.toString().toByteArray(Charsets.UTF_8))}"
        return AccessToken("$signingInput.${encode(sign(signingInput))}", expiresAt.toEpochMilli())
    }

    private fun sign(signingInput: String): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(signingInput.toByteArray(Charsets.US_ASCII))
    }

    private fun encode(bytes: ByteArray): String = ENCODER.encodeToString(bytes)

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val MINIMUM_KEY_SIZE = 32
        const val LIFETIME_HOURS = 1L
        const val HEADER = """{"alg":"HS256","typ":"JWT"}"""

        val DEFAULT_LIFETIME: Duration = Duration.ofHours(LIFETIME_HOURS)
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val ENCODED_HEADER: String = ENCODER.encodeToString(HEADER.toByteArray(Charsets.UTF_8))
    }
}
