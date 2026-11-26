package com.lnight.valence.core.model.repository

import com.lnight.valence.core.model.Contact
import com.lnight.valence.core.model.ContactId
import kotlinx.coroutines.flow.Flow

interface ContactRepository {
    fun observeContacts(): Flow<List<Contact>>

    suspend fun findContact(contactId: ContactId): Contact?
}
