package com.lnight.valence.protocol.frame

import com.lnight.valence.protocol.ProtocolVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ServerFrame

@Serializable
@SerialName("message.deliver")
data class MessageDeliverFrame(
    val v: Int = ProtocolVersion.CURRENT,
    val serverSeq: Long,
    val serverTs: Long,
    val envelope: String,
) : ServerFrame

@Serializable
@SerialName("error")
data class ErrorFrame(
    val v: Int = ProtocolVersion.CURRENT,
    val code: ErrorCode = ErrorCode.UNKNOWN,
    val retryAfterMs: Long? = null,
) : ServerFrame