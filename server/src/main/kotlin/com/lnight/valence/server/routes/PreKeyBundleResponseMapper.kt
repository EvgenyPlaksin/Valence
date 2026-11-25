package com.lnight.valence.server.routes

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.http.KyberPreKeyDto
import com.lnight.valence.protocol.http.OneTimePreKeyDto
import com.lnight.valence.protocol.http.PreKeyBundleResponse
import com.lnight.valence.protocol.http.SignedPreKeyDto
import com.lnight.valence.server.domain.PreKeyBundle
import com.lnight.valence.server.domain.SignedPublicKey

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
                bundle.oneTimeKyberPreKey?.let {
                    KyberPreKeyDto(it.id, Base64Url.encode(it.publicKey), Base64Url.encode(it.signature))
                } ?: bundle.kyberPreKey.toDto(),
            oneTimePreKey = bundle.oneTimePreKey?.let { OneTimePreKeyDto(it.id, Base64Url.encode(it.publicKey)) },
        )

    private fun SignedPublicKey.toDto() = KyberPreKeyDto(id, Base64Url.encode(publicKey), Base64Url.encode(signature))
}
