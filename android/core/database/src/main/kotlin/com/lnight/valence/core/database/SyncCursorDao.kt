package com.lnight.valence.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface SyncCursorDao {
    @Query("SELECT last_confirmed_seq FROM sync_cursor WHERE id = 1")
    suspend fun getLastConfirmedSeq(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setLastConfirmedSeq(entity: SyncCursorEntity)
}
