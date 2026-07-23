package com.smartreimburse.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LocalDeletionDao {
    @Query("SELECT * FROM local_deletions ORDER BY id ASC")
    suspend fun getPending(): List<LocalDeletionEntity>

    @Insert
    suspend fun insert(entity: LocalDeletionEntity): Long

    @Delete
    suspend fun delete(entity: LocalDeletionEntity)
}
