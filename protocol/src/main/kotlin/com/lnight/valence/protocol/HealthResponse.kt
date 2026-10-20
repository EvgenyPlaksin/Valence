package com.lnight.valence.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    @SerialName("v")
    val version: Int = 1,
    val status: String = "ok",
)
