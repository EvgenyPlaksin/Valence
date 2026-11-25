package com.lnight.valence.server.routes

import com.lnight.valence.protocol.http.UpdateDeliveryKeyRequest

class UpdateDeliveryKeyRequestMapper(
    private val deliveryKeyHashMapper: DeliveryKeyHashMapper,
) {
    fun toDomain(request: UpdateDeliveryKeyRequest): ByteArray? =
        deliveryKeyHashMapper.toDomain(request.deliveryKeyHash)
}
