package com.smartreimburse.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "projects",
    indices = [Index(value = ["remoteId"], unique = true)]
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val remoteId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val syncVersion: Long = 0,
    @ColumnInfo(defaultValue = "'PENDING'")
    val syncState: String = SyncState.PENDING,
    val clientMutationId: String? = null
)
