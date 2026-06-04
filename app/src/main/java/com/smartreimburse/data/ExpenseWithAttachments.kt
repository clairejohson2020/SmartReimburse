package com.smartreimburse.data

import androidx.room.Embedded
import androidx.room.Relation

data class ExpenseWithAttachments(
    @Embedded
    val expense: ExpenseEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "expenseId"
    )
    val attachments: List<AttachmentEntity>
)
