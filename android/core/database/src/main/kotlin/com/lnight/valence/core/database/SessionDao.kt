package com.lnight.valence.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface SessionDao {
    @Query("SELECT * FROM signal_session WHERE contact_id = :contactId")
    suspend fun getSession(contactId: String): SignalSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(entity: SignalSessionEntity)

    @Query("DELETE FROM signal_session WHERE contact_id = :contactId")
    suspend fun deleteSession(contactId: String): Int
}
