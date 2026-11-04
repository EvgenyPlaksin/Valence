package com.lnight.valence.server.routes

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.http.UploadOneTimePreKeysRequest
import com.lnight.valence.server.domain.OneTimePreKey

class UploadOneTimePreKeysRequestMapper {
    fun toDomain(request: UploadOneTimePreKeysRequest): List<OneTimePreKey>? =
        Parsing.orNull { request.oneTimePreKeys.map { OneTimePreKey(it.id, Base64Url.decode(it.pub)) } }
}
