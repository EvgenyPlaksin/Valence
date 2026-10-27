package com.lnight.valence.server.routes

import com.lnight.valence.protocol.Base64Url
import com.lnight.valence.protocol.http.RegisterRequest
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.Registration
import com.lnight.valence.server.domain.SignedPublicKey

class RegisterRequestMapper {
    fun toDomain(request: RegisterRequest): Registration? =
        Parsing.orNull {
            Registration(
                challenge = Base64Url.decode(request.challenge),
                mailboxId = request.mailboxId,
                identityKey = Base64Url.decode(request.identityKey),
                signedPreKey = signed(request.signedPreKey.id, request.signedPreKey.pub, request.signedPreKey.sig),
                kyberPreKey = signed(request.kyberPreKey.id, request.kyberPreKey.pub, request.kyberPreKey.sig),
                oneTimePreKeys = request.oneTimePreKeys.map { OneTimePreKey(it.id, Base64Url.decode(it.pub)) },
                deliveryKeyHash = Base64Url.decode(request.deliveryKeyHash),
                proof = Base64Url.decode(request.proof),
            )
        }

    private fun signed(
        id: Int,
        publicKey: String,
        signature: String,
    ): SignedPublicKey = SignedPublicKey(id, Base64Url.decode(publicKey), Base64Url.decode(signature))
}
