package com.lnight.valence.core.database

import com.lnight.valence.core.model.ContactId

data class SignalSessionStorage(
    val contactId: ContactId,
    val recordBlob: ImmutableBytes,
    val recordIv: ImmutableBytes,
    val recordVersion: Int,
    val updatedAtMillis: Long,
)
