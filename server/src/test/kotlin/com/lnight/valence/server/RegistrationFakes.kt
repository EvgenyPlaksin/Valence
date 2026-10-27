package com.lnight.valence.server

import com.lnight.valence.server.domain.Mailbox
import com.lnight.valence.server.domain.MailboxStore
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.PublicKeyVerifier

internal class Verification(
    val message: ByteArray,
    val signature: ByteArray,
)

internal class FakePublicKeyVerifier(
    private val isKeyValid: (ByteArray) -> Boolean = { true },
    private val isSignatureValid: (ByteArray) -> Boolean = { true },
) : PublicKeyVerifier {
    val verifications = mutableListOf<Verification>()

    override fun isValidEcKey(bytes: ByteArray): Boolean = isKeyValid(bytes)

    override fun isValidKemKey(bytes: ByteArray): Boolean = isKeyValid(bytes)

    override fun verifySignature(
        signerKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        verifications += Verification(message, signature)
        return isSignatureValid(signature)
    }
}

internal class RecordingMailboxStore(
    private val accepts: Boolean = true,
) : MailboxStore {
    val created = mutableListOf<Mailbox>()
    val createdOneTimeKeys = mutableListOf<List<OneTimePreKey>>()

    override fun create(
        mailbox: Mailbox,
        oneTimePreKeys: List<OneTimePreKey>,
    ): Boolean {
        if (accepts) {
            created += mailbox
            createdOneTimeKeys += oneTimePreKeys
        }
        return accepts
    }

    override fun find(mailboxId: String): Mailbox? = created.firstOrNull { it.id == mailboxId }
}
