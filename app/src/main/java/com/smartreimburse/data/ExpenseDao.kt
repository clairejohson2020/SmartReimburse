package com.smartreimburse.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Query(
        """
        SELECT * FROM expenses
        WHERE (:keyword = ''
            OR name LIKE '%' || :keyword || '%'
            OR model LIKE '%' || :keyword || '%'
            OR notes LIKE '%' || :keyword || '%'
            OR invoiceNumber LIKE '%' || :keyword || '%'
            OR onlineLink LIKE '%' || :keyword || '%')
        AND (:fromDate IS NULL OR date >= :fromDate)
        AND (:toDate IS NULL OR date <= :toDate)
        AND (:minAmount IS NULL OR totalAmount >= :minAmount)
        AND (:maxAmount IS NULL OR totalAmount <= :maxAmount)
        AND (:invoiceFilter IS NULL OR hasInvoice = :invoiceFilter)
        ORDER BY date DESC
        """
    )
    fun observeFilteredExpenses(
        keyword: String,
        fromDate: Long?,
        toDate: Long?,
        minAmount: Double?,
        maxAmount: Double?,
        invoiceFilter: Boolean?
    ): Flow<List<ExpenseEntity>>

    @Query("SELECT COALESCE(SUM(totalAmount), 0) FROM expenses")
    fun observeTotalSpent(): Flow<Double>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getExpense(id: Long): ExpenseEntity?

    @Transaction
    @Query("SELECT * FROM expenses WHERE id = :id")
    fun observeExpenseWithAttachments(id: Long): Flow<ExpenseWithAttachments?>

    @Transaction
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getExpenseWithAttachments(id: Long): ExpenseWithAttachments?

    @Transaction
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    suspend fun getAllExpenseWithAttachments(): List<ExpenseWithAttachments>

    @Query("SELECT * FROM attachments WHERE expenseId = :expenseId")
    suspend fun getAttachmentsForExpense(expenseId: Long): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Update
    suspend fun updateExpense(expense: ExpenseEntity)

    @Delete
    suspend fun deleteExpense(expense: ExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM attachments WHERE expenseId = :expenseId")
    suspend fun deleteAttachmentsForExpense(expenseId: Long)

    @Transaction
    suspend fun insertExpenseWithAttachments(
        expense: ExpenseEntity,
        attachments: List<AttachmentEntity>
    ): Long {
        val expenseId = insertExpense(expense)
        if (attachments.isNotEmpty()) {
            insertAttachments(attachments.map { it.copy(expenseId = expenseId) })
        }
        return expenseId
    }

    @Transaction
    suspend fun updateExpenseWithAttachments(
        expense: ExpenseEntity,
        attachments: List<AttachmentEntity>
    ) {
        updateExpense(expense)
        deleteAttachmentsForExpense(expense.id)
        if (attachments.isNotEmpty()) {
            insertAttachments(attachments.map { it.copy(expenseId = expense.id) })
        }
    }
}
