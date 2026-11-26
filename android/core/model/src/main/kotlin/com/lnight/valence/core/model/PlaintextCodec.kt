package com.lnight.valence.core.model

interface PlaintextCodec {
    fun encode(plaintext: Plaintext): PlaintextCodecResult<ByteArray>

    fun decode(bytes: ByteArray): PlaintextCodecResult<Plaintext>
}

sealed interface PlaintextCodecResult<out T> {
    data class Success<T>(
        val value: T,
    ) : PlaintextCodecResult<T>

    data class Failure(
        val reason: PlaintextCodecFailure,
    ) : PlaintextCodecResult<Nothing>
}

enum class PlaintextCodecFailure {
    InvalidPayload,
    MalformedUtf8,
    UnsupportedAttachments,
}
