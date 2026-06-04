package com.smartreimburse.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
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
