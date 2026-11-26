package com.lnight.valence.core.database

import com.lnight.valence.core.model.ContactId

interface LocalOutboxStore {
    suspend fun dueItems(nowMillis: Long): List<StoredOutboxItem>

    suspend fun pauseForContact(contactId: ContactId): Int

    suspend fun resumeForContact(
        contactId: ContactId,
        nowMillis: Long,
    ): Int

    suspend fun failExpired(nowMillis: Long): Int
}
