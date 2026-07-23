package com.smartreimburse.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_deletions")
data class LocalDeletionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val entityType: String,
    val remoteId: String,
    val baseVersion: Long,
    val clientMutationId: String,
    val createdAt: Long
)
