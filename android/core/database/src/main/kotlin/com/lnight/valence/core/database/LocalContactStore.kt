package com.lnight.valence.core.database

import com.lnight.valence.core.model.Contact
import com.lnight.valence.core.model.ContactId
import kotlinx.coroutines.flow.Flow

interface LocalContactStore {
    fun observeContacts(): Flow<List<Contact>>

    suspend fun getContact(contactId: ContactId): Contact?

    suspend fun putContact(
        contact: Contact,
        keys: ContactKeyStorage,
    )
}
