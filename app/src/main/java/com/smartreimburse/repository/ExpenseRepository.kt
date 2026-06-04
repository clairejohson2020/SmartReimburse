package com.smartreimburse.repository

import com.smartreimburse.data.AdvanceFundDao
import com.smartreimburse.data.AdvanceFundEntity
import com.smartreimburse.data.AttachmentEntity
import com.smartreimburse.data.ExpenseDao
import com.smartreimburse.data.ExpenseEntity
import com.smartreimburse.data.ExpenseWithAttachments
import com.smartreimburse.viewmodel.ExpenseFilterUiState
import com.smartreimburse.viewmodel.InvoiceFilter
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ExpenseRepository(
    private val expenseDao: ExpenseDao,
    private val advanceFundDao: AdvanceFundDao
) {
    private val inputDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val zoneId = ZoneId.systemDefault()

    fun observeAdvanceFund(): Flow<Double> {
        return advanceFundDao.observeAdvanceFund().map { it?.totalFund ?: 0.0 }
    }

    fun observeTotalSpent(): Flow<Double> = expenseDao.observeTotalSpent()

    fun observeFilteredExpenses(filter: ExpenseFilterUiState): Flow<List<ExpenseEntity>> {
        return expenseDao.observeFilteredExpenses(
            keyword = filter.keyword.trim(),
            fromDate = filter.fromDateText.toStartOfDayMillisOrNull(),
            toDate = filter.toDateText.toEndOfDayMillisOrNull(),
            minAmount = filter.minAmountText.toDoubleOrNull(),
            maxAmount = filter.maxAmountText.toDoubleOrNull(),
            invoiceFilter = when (filter.invoiceFilter) {
                InvoiceFilter.ALL -> null
                InvoiceFilter.WITH_INVOICE -> true
                InvoiceFilter.WITHOUT_INVOICE -> false
            }
        )
    }

    fun observeExpenseWithAttachments(id: Long): Flow<ExpenseWithAttachments?> {
        return expenseDao.observeExpenseWithAttachments(id)
    }

    suspend fun setAdvanceFund(totalFund: Double) {
        advanceFundDao.upsert(AdvanceFundEntity(totalFund = totalFund))
    }

    suspend fun getExpenseWithAttachments(id: Long): ExpenseWithAttachments? {
        return expenseDao.getExpenseWithAttachments(id)
    }

    suspend fun getAllExpenseWithAttachments(): List<ExpenseWithAttachments> {
        return expenseDao.getAllExpenseWithAttachments()
    }

    suspend fun saveExpense(
        expense: ExpenseEntity,
        attachments: List<AttachmentEntity>
    ): Long {
        return if (expense.id == 0L) {
            expenseDao.insertExpenseWithAttachments(expense, attachments)
        } else {
            val retainedPaths = attachments.map { it.filePath }.toSet()
            expenseDao.getAttachmentsForExpense(expense.id)
                .filterNot { it.filePath in retainedPaths }
                .forEach { removed -> runCatching { File(removed.filePath).delete() } }
            expenseDao.updateExpenseWithAttachments(expense, attachments)
            expense.id
        }
    }

    suspend fun deleteExpenseAndFiles(expenseId: Long) {
        val detail = expenseDao.getExpenseWithAttachments(expenseId) ?: return
        detail.attachments.forEach { attachment ->
            runCatching { File(attachment.filePath).delete() }
        }
        expenseDao.deleteExpense(detail.expense)
    }

    private fun String.toStartOfDayMillisOrNull(): Long? {
        return trim().takeIf { it.isNotBlank() }?.let {
            runCatching {
                LocalDate.parse(it, inputDateFormatter)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        }
    }

    private fun String.toEndOfDayMillisOrNull(): Long? {
        return trim().takeIf { it.isNotBlank() }?.let {
            runCatching {
                LocalDate.parse(it, inputDateFormatter)
                    .atTime(LocalTime.MAX)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        }
    }
}
