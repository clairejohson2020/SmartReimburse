package com.smartreimburse.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AdvanceFundDao {
    @Query("SELECT * FROM advance_fund WHERE projectId = :projectId")
    fun observeAdvanceFund(projectId: Long): Flow<AdvanceFundEntity?>

    @Query("SELECT * FROM advance_fund WHERE projectId = :projectId")
    suspend fun getAdvanceFund(projectId: Long): AdvanceFundEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AdvanceFundEntity)

    @Query("DELETE FROM advance_fund WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: Long)
}
