package com.lnight.valence.server.crypto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoType

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class ServerCertificateBody(
    @ProtoNumber(1) val id: Int? = null,
    @ProtoNumber(2) val key: ByteArray? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class ServerCertificateEnvelope(
    @ProtoNumber(1) val certificate: ByteArray? = null,
    @ProtoNumber(2) val signature: ByteArray? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@Suppress("MagicNumber")
internal class SenderCertificateBody(
    @ProtoNumber(1) val senderE164: String? = null,
    @ProtoNumber(2) val senderDevice: Int? = null,
    @ProtoNumber(3) @ProtoType(ProtoIntegerType.FIXED) val expires: Long? = null,
    @ProtoNumber(4) val identityKey: ByteArray? = null,
    @ProtoNumber(5) val signer: ServerCertificateEnvelope? = null,
    @ProtoNumber(6) val senderUuid: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class SenderCertificateEnvelope(
    @ProtoNumber(1) val certificate: ByteArray? = null,
    @ProtoNumber(2) val signature: ByteArray? = null,
)
