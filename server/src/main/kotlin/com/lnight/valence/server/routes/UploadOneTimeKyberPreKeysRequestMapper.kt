package com.lnight.valence.server.routes

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.http.UploadOneTimeKyberPreKeysRequest
import com.lnight.valence.server.domain.OneTimeKyberPreKey

class UploadOneTimeKyberPreKeysRequestMapper {
    fun toDomain(request: UploadOneTimeKyberPreKeysRequest): List<OneTimeKyberPreKey>? =
        Parsing.orNull {
            request.oneTimeKyberPreKeys.map {
                OneTimeKyberPreKey(it.id, Base64Url.decode(it.pub), Base64Url.decode(it.sig))
            }
        }
}
