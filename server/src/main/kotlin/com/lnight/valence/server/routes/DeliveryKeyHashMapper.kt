package com.lnight.valence.server.routes

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.server.domain.WireSizes

class DeliveryKeyHashMapper {
    fun toDomain(encoded: String): ByteArray? =
        Parsing.orNull {
            val decoded = Base64Url.decode(encoded)
            require(decoded.size == WireSizes.HASH)
            require(Base64Url.encode(decoded) == encoded)
            decoded
        }
}
