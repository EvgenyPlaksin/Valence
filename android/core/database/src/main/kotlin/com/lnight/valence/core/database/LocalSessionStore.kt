package com.lnight.valence.core.database

import com.lnight.valence.core.model.ContactId

interface LocalSessionStore {
    suspend fun getSession(contactId: ContactId): SignalSessionStorage?

    suspend fun putSession(session: SignalSessionStorage)
}
