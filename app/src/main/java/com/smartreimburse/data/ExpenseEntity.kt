package com.smartreimburse.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "expenses",
    indices = [Index("projectId"), Index(value = ["remoteId"], unique = true)]
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(defaultValue = "1")
    val projectId: Long = 1,
    val name: String,
    val model: String,
    val quantity: Int,
    val price: Double,
    val totalAmount: Double,
    @ColumnInfo(defaultValue = "0")
    val priceCents: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val amountCents: Long = 0,
    val date: Long,
    val hasInvoice: Boolean,
    val invoiceNumber: String?,
    val onlineLink: String?,
    val notes: String?,
    val isReimbursed: Boolean,
    val remoteId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val syncVersion: Long = 0,
    @ColumnInfo(defaultValue = "'PENDING'")
    val syncState: String = SyncState.PENDING,
    val clientMutationId: String? = null
)
