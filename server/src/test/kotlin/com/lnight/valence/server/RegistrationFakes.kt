package com.lnight.valence.server

import com.lnight.valence.server.domain.Mailbox
import com.lnight.valence.server.domain.MailboxStore
import com.lnight.valence.server.domain.OneTimePreKey
import com.lnight.valence.server.domain.PublicKeyVerifier
import com.lnight.valence.server.store.InMemoryMailboxStore

internal class Verification(
    val signerKey: ByteArray,
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
        verifications += Verification(signerKey, message, signature)
        return isSignatureValid(signature)
    }
}

internal class RecordingMailboxStore(
    private val accepts: Boolean = true,
    private val delegate: MailboxStore = InMemoryMailboxStore(),
) : MailboxStore by delegate {
    val created = mutableListOf<Mailbox>()
    val createdOneTimeKeys = mutableListOf<List<OneTimePreKey>>()

    override fun create(
        mailbox: Mailbox,
        oneTimePreKeys: List<OneTimePreKey>,
    ): Boolean {
        if (accepts) {
            created += mailbox
            createdOneTimeKeys += oneTimePreKeys
            delegate.create(mailbox, oneTimePreKeys)
        }
        return accepts
    }
}
