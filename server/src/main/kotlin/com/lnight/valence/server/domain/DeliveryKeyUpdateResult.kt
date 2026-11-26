package com.lnight.valence.server.domain

sealed interface DeliveryKeyUpdateResult {
    data object Updated : DeliveryKeyUpdateResult

    data object UnknownMailbox : DeliveryKeyUpdateResult

    data object Malformed : DeliveryKeyUpdateResult
}
