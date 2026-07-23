package com.smartreimburse.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "advance_fund")
data class AdvanceFundEntity(
    @PrimaryKey
    val projectId: Long,
    val totalFund: Double,
    @ColumnInfo(defaultValue = "0")
    val totalFundCents: Long = 0
)
