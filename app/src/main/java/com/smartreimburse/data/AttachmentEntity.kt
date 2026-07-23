package com.smartreimburse.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = ExpenseEntity::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("expenseId"), Index(value = ["remoteId"], unique = true)]
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val expenseId: Long,
    val type: AttachmentType,
    val filePath: String,
    val remoteId: String? = null,
    val cloudFileId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val syncVersion: Long = 0,
    @ColumnInfo(defaultValue = "'PENDING'")
    val syncState: String = SyncState.PENDING
)
