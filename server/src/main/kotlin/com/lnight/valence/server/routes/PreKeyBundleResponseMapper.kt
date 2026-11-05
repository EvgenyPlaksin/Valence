package com.lnight.valence.server.routes

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.http.KyberPreKeyDto
import com.lnight.valence.protocol.http.OneTimePreKeyDto
import com.lnight.valence.protocol.http.PreKeyBundleResponse
import com.lnight.valence.protocol.http.SignedPreKeyDto
import com.lnight.valence.server.domain.PreKeyBundle

class PreKeyBundleResponseMapper {
    fun toResponse(bundle: PreKeyBundle): PreKeyBundleResponse =
        PreKeyBundleResponse(
            identityKey = Base64Url.encode(bundle.identityKey),
            signedPreKey =
                SignedPreKeyDto(
                    id = bundle.signedPreKey.id,
                    pub = Base64Url.encode(bundle.signedPreKey.publicKey),
                    sig = Base64Url.encode(bundle.signedPreKey.signature),
                ),
            kyberPreKey =
                KyberPreKeyDto(
                    id = bundle.kyberPreKey.id,
                    pub = Base64Url.encode(bundle.kyberPreKey.publicKey),
                    sig = Base64Url.encode(bundle.kyberPreKey.signature),
                ),
            oneTimePreKey = bundle.oneTimePreKey?.let { OneTimePreKeyDto(it.id, Base64Url.encode(it.publicKey)) },
        )
}
