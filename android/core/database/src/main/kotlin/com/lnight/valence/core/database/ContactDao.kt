package com.lnight.valence.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ContactDao {
    @Query("SELECT * FROM contact ORDER BY display_name ASC")
    fun observeContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contact WHERE contact_id = :contactId")
    suspend fun getContact(contactId: String): ContactEntity?

    @Query("SELECT * FROM contact WHERE mailbox_id = :mailboxId")
    suspend fun getContactByMailboxId(mailboxId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(entity: ContactEntity)

    @Query("DELETE FROM contact WHERE contact_id = :contactId")
    suspend fun deleteContact(contactId: String): Int
}
