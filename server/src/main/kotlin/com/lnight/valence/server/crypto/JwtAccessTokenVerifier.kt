package com.lnight.valence.server.crypto

import com.lnight.valence.server.domain.AccessTokenVerifier
import com.lnight.valence.server.domain.MailboxIdFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class JwtAccessTokenVerifier(
    private val clock: Clock,
    key: ByteArray,
) : AccessTokenVerifier {
    private val keySpec: SecretKeySpec

    init {
        require(key.size >= MINIMUM_KEY_SIZE) { "The token key needs at least $MINIMUM_KEY_SIZE bytes" }
        keySpec = SecretKeySpec(key, ALGORITHM)
    }

    override fun verify(token: String): String? {
        val parts = token.split('.')
        return if (parts.size == PART_COUNT && isAuthentic(parts)) validSubject(parts[1]) else null
    }

    private fun isAuthentic(parts: List<String>): Boolean {
        val signature = decode(parts[2])
        return isHs256(parts[0]) &&
            signature != null &&
            MessageDigest.isEqual(sign("${parts[0]}.${parts[1]}"), signature)
    }

    private fun isHs256(encodedHeader: String): Boolean = parseObject(encodedHeader)?.get("alg").asText() == HS256

    private fun validSubject(encodedClaims: String): String? {
        val claims = parseObject(encodedClaims)
        val subject = claims?.get("sub").asText()
        val expiresAt = (claims?.get("exp") as? JsonPrimitive)?.longOrNull
        return subject?.takeIf {
            MailboxIdFormat.isValid(it) && expiresAt != null && expiresAt > clock.instant().epochSecond
        }
    }

    private fun sign(signingInput: String): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(signingInput.toByteArray(Charsets.US_ASCII))
    }

    private fun decode(text: String): ByteArray? = runCatching { DECODER.decode(text) }.getOrNull()

    private fun parseObject(encoded: String): JsonObject? =
        decode(encoded)
            ?.let { runCatching { Json.parseToJsonElement(String(it, Charsets.UTF_8)) }.getOrNull() } as? JsonObject

    private fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val HS256 = "HS256"
        const val MINIMUM_KEY_SIZE = 32
        const val PART_COUNT = 3

        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
    }
}
