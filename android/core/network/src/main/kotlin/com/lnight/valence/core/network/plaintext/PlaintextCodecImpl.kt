package com.lnight.valence.core.network.plaintext

import com.lnight.valence.core.model.Plaintext
import com.lnight.valence.core.model.PlaintextCodec
import com.lnight.valence.core.model.PlaintextCodecFailure
import com.lnight.valence.core.model.PlaintextCodecResult
import com.lnight.valence.protocol.plaintext.PlaintextPayload
import kotlinx.serialization.SerializationException
import java.nio.charset.CharacterCodingException
import javax.inject.Inject
import com.lnight.valence.protocol.plaintext.PlaintextCodec as ProtocolPlaintextCodec

class PlaintextCodecImpl
    @Inject
    constructor(
        private val mapper: PlaintextPayloadMapper,
    ) : PlaintextCodec {
        override fun encode(plaintext: Plaintext): PlaintextCodecResult<ByteArray> =
            try {
                PlaintextCodecResult.Success(ProtocolPlaintextCodec.encode(mapper.toPayload(plaintext)))
            } catch (_: SerializationException) {
                PlaintextCodecResult.Failure(PlaintextCodecFailure.InvalidPayload)
            }

        override fun decode(bytes: ByteArray): PlaintextCodecResult<Plaintext> {
            val validUtf8 = isValidUtf8(bytes)
            val payload = if (validUtf8) decodePayload(bytes) else null

            return when {
                !validUtf8 -> {
                    PlaintextCodecResult.Failure(PlaintextCodecFailure.MalformedUtf8)
                }

                payload == null -> {
                    PlaintextCodecResult.Failure(PlaintextCodecFailure.InvalidPayload)
                }

                payload.attachments.isNotEmpty() -> {
                    PlaintextCodecResult.Failure(PlaintextCodecFailure.UnsupportedAttachments)
                }

                else -> {
                    mapper
                        .toPlaintext(payload)
                        ?.let { PlaintextCodecResult.Success(it) }
                        ?: PlaintextCodecResult.Failure(PlaintextCodecFailure.InvalidPayload)
                }
            }
        }

        private fun isValidUtf8(bytes: ByteArray): Boolean =
            try {
                bytes.decodeToString(throwOnInvalidSequence = true)
                true
            } catch (_: CharacterCodingException) {
                false
            }

        private fun decodePayload(bytes: ByteArray): PlaintextPayload? =
            try {
                ProtocolPlaintextCodec.decode(bytes)
            } catch (_: SerializationException) {
                null
            }
    }
