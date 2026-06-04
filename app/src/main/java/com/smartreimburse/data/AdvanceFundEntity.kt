package com.smartreimburse.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "advance_fund")
data class AdvanceFundEntity(
    @PrimaryKey
    val projectId: Long,
    val totalFund: Double
)
