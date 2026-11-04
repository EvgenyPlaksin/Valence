package com.lnight.valence.server.routes

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.http.ReplaceSignedPreKeysRequest
import com.lnight.valence.server.domain.PreKeyReplacement
import com.lnight.valence.server.domain.SignedPublicKey

class ReplaceSignedPreKeysRequestMapper {
    fun toDomain(request: ReplaceSignedPreKeysRequest): PreKeyReplacement? =
        Parsing.orNull {
            PreKeyReplacement(
                signedPreKey =
                    SignedPublicKey(
                        request.signedPreKey.id,
                        Base64Url.decode(request.signedPreKey.pub),
                        Base64Url.decode(request.signedPreKey.sig),
                    ),
                kyberPreKey =
                    SignedPublicKey(
                        request.kyberPreKey.id,
                        Base64Url.decode(request.kyberPreKey.pub),
                        Base64Url.decode(request.kyberPreKey.sig),
                    ),
            )
        }
}
