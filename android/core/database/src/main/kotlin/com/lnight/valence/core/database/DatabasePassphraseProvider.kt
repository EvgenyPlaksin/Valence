package com.lnight.valence.core.database

interface DatabasePassphraseProvider {
    fun takePassphrase(): ByteArray
}
