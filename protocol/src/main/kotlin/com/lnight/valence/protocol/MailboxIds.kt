package com.lnight.valence.protocol

import java.nio.ByteBuffer
import java.util.UUID

object MailboxIds {
    private const val PREFIX = "mbx_"
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private const val ENCODED_LENGTH = 26
    private const val BYTE_COUNT = 16
    private const val BITS_PER_CHAR = 5
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF
    private const val CHAR_MASK = 0x1F

    fun toUuid(mailboxId: String): String? =
        decode(mailboxId)?.let { bytes ->
            val buffer = ByteBuffer.wrap(bytes)
            UUID(buffer.long, buffer.long).toString()
        }

    fun fromUuid(uuid: String): String? {
        val parsed = runCatching { UUID.fromString(uuid) }.getOrNull()
        return parsed?.takeIf { it.toString() == uuid }?.let {
            val bytes = ByteBuffer.allocate(BYTE_COUNT).putLong(it.mostSignificantBits).putLong(it.leastSignificantBits)
            PREFIX + encode(bytes.array())
        }
    }

    private fun decode(mailboxId: String): ByteArray? {
        val body = mailboxId.removePrefix(PREFIX)
        val valid = mailboxId.startsWith(PREFIX) && body.length == ENCODED_LENGTH && body.all { it in ALPHABET }
        return if (valid) unpack(body) else null
    }

    private fun unpack(body: String): ByteArray? {
        val bytes = ByteArray(BYTE_COUNT)
        var buffer = 0
        var bits = 0
        var index = 0
        for (char in body) {
            buffer = (buffer shl BITS_PER_CHAR) or ALPHABET.indexOf(char)
            bits += BITS_PER_CHAR
            if (bits >= BITS_PER_BYTE) {
                bits -= BITS_PER_BYTE
                bytes[index++] = ((buffer shr bits) and BYTE_MASK).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return if (buffer == 0) bytes else null
    }

    private fun encode(bytes: ByteArray): String {
        val output = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl BITS_PER_BYTE) or (byte.toInt() and BYTE_MASK)
            bits += BITS_PER_BYTE
            while (bits >= BITS_PER_CHAR) {
                bits -= BITS_PER_CHAR
                output.append(ALPHABET[(buffer shr bits) and CHAR_MASK])
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        if (bits > 0) output.append(ALPHABET[(buffer shl (BITS_PER_CHAR - bits)) and CHAR_MASK])
        return output.toString()
    }
}
