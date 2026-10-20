package com.lnight.valence.protocol.frame

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ClientFrame

@Serializable
@SerialName("delivery.confirm")
data class DeliveryConfirmFrame(
    val v: Int = ProtocolVersion.CURRENT,
    val upToServerSeq: Long,
) : ClientFrame

@Serializable
@SerialName("sync.resume")
data class SyncResumeFrame(
    val v: Int = ProtocolVersion.CURRENT,
    val afterServerSeq: Long,
) : ClientFrame
