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
        WHERE projectId = :projectId
        AND (:keyword = ''
            OR name LIKE '%' || :keyword || '%'
            OR model LIKE '%' || :keyword || '%'
            OR notes LIKE '%' || :keyword || '%'
            OR invoiceNumber LIKE '%' || :keyword || '%'
            OR onlineLink LIKE '%' || :keyword || '%')
        AND (:fromDate IS NULL OR date >= :fromDate)
        AND (:toDate IS NULL OR date <= :toDate)
        AND (:minAmountCents IS NULL OR amountCents >= :minAmountCents)
        AND (:maxAmountCents IS NULL OR amountCents <= :maxAmountCents)
        AND (:invoiceFilter IS NULL OR hasInvoice = :invoiceFilter)
        ORDER BY date DESC
        """
    )
    fun observeFilteredExpenses(
        projectId: Long,
        keyword: String,
        fromDate: Long?,
        toDate: Long?,
        minAmountCents: Long?,
        maxAmountCents: Long?,
        invoiceFilter: Boolean?
    ): Flow<List<ExpenseEntity>>

    @Query("SELECT COALESCE(SUM(amountCents), 0) FROM expenses WHERE projectId = :projectId")
    fun observeTotalSpentCents(projectId: Long): Flow<Long>

    @Query("SELECT * FROM expenses WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getExpenseByRemoteId(remoteId: String): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE syncState != 'SYNCED'")
    suspend fun getPendingExpenses(): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getExpense(id: Long): ExpenseEntity?

    @Query("SELECT COUNT(*) FROM expenses WHERE projectId = :projectId")
    suspend fun countExpensesForProject(projectId: Long): Int

    @Transaction
    @Query("SELECT * FROM expenses WHERE id = :id")
    fun observeExpenseWithAttachments(id: Long): Flow<ExpenseWithAttachments?>

    @Transaction
    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getExpenseWithAttachments(id: Long): ExpenseWithAttachments?

    @Transaction
    @Query("SELECT * FROM expenses WHERE projectId = :projectId ORDER BY date DESC")
    suspend fun getAllExpenseWithAttachments(projectId: Long): List<ExpenseWithAttachments>

    @Query("SELECT * FROM attachments WHERE expenseId = :expenseId")
    suspend fun getAttachmentsForExpense(expenseId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getAttachmentByRemoteId(remoteId: String): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Update
    suspend fun updateExpense(expense: ExpenseEntity)

    @Update
    suspend fun updateAttachment(attachment: AttachmentEntity)

    @Query("UPDATE expenses SET remoteId = :remoteId, syncVersion = :version, syncState = 'SYNCED', clientMutationId = NULL WHERE id = :id")
    suspend fun markExpenseSynced(id: Long, remoteId: String, version: Long)

    @Query("UPDATE expenses SET syncVersion = :remoteVersion, syncState = 'CONFLICT' WHERE id = :id")
    suspend fun markExpenseConflict(id: Long, remoteVersion: Long)

    @Query("SELECT * FROM expenses WHERE syncState = 'CONFLICT'")
    suspend fun getExpenseConflicts(): List<ExpenseEntity>

    @Query("UPDATE attachments SET remoteId = :remoteId, syncVersion = :version, syncState = 'SYNCED' WHERE id = :id")
    suspend fun markAttachmentSynced(id: Long, remoteId: String, version: Long)

    @Query("DELETE FROM attachments WHERE remoteId = :remoteId")
    suspend fun deleteAttachmentByRemoteId(remoteId: String)

    @Query("DELETE FROM expenses WHERE remoteId = :remoteId")
    suspend fun deleteExpenseByRemoteId(remoteId: String)

    @Delete
    suspend fun deleteExpense(expense: ExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM attachments WHERE expenseId = :expenseId")
    suspend fun deleteAttachmentsForExpense(expenseId: Long)

    @Query("DELETE FROM expenses WHERE projectId = :projectId")
    suspend fun deleteExpensesForProject(projectId: Long)

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
