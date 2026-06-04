package com.smartreimburse.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AdvanceFundDao {
    @Query("SELECT * FROM advance_fund WHERE id = 1")
    fun observeAdvanceFund(): Flow<AdvanceFundEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AdvanceFundEntity)
}
