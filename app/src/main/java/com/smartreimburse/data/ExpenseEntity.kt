package com.smartreimburse.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "expenses",
    indices = [Index("projectId")]
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
    val date: Long,
    val hasInvoice: Boolean,
    val invoiceNumber: String?,
    val onlineLink: String?,
    val notes: String?,
    val isReimbursed: Boolean
)
