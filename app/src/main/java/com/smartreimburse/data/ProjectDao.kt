package com.smartreimburse.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt ASC, id ASC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY createdAt ASC, id ASC")
    suspend fun getProjects(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProject(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getProjectByRemoteId(remoteId: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE remoteId IS NULL AND name = :name ORDER BY id ASC LIMIT 1")
    suspend fun getUnlinkedProjectByName(name: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE syncState != 'SYNCED'")
    suspend fun getPendingProjects(): List<ProjectEntity>

    @Query("SELECT * FROM projects ORDER BY createdAt ASC, id ASC LIMIT 1")
    suspend fun getFirstProject(): ProjectEntity?

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun countProjects(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("UPDATE projects SET remoteId = :remoteId, syncVersion = :version, syncState = 'SYNCED', clientMutationId = NULL WHERE id = :id")
    suspend fun markSynced(id: Long, remoteId: String, version: Long)

    @Query("UPDATE projects SET syncVersion = :remoteVersion, syncState = 'CONFLICT' WHERE id = :id")
    suspend fun markConflict(id: Long, remoteVersion: Long)

    @Query("SELECT * FROM projects WHERE syncState = 'CONFLICT'")
    suspend fun getConflicts(): List<ProjectEntity>

    @Query("DELETE FROM projects WHERE remoteId = :remoteId")
    suspend fun deleteByRemoteId(remoteId: String)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: Long)
}
