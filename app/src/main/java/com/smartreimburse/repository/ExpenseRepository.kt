package com.smartreimburse.repository

import com.smartreimburse.data.AdvanceFundDao
import com.smartreimburse.data.AdvanceFundEntity
import com.smartreimburse.data.AttachmentEntity
import com.smartreimburse.data.ExpenseDao
import com.smartreimburse.data.ExpenseEntity
import com.smartreimburse.data.ExpenseWithAttachments
import com.smartreimburse.data.ProjectDao
import com.smartreimburse.data.ProjectEntity
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
    private val projectDao: ProjectDao,
    private val expenseDao: ExpenseDao,
    private val advanceFundDao: AdvanceFundDao
) {
    private val inputDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val zoneId = ZoneId.systemDefault()

    fun observeProjects(): Flow<List<ProjectEntity>> = projectDao.observeProjects()

    suspend fun ensureInitialProject(preferredProjectId: Long?): ProjectEntity {
        preferredProjectId
            ?.let { projectDao.getProject(it) }
            ?.let { return it }

        projectDao.getFirstProject()?.let { return it }

        val now = System.currentTimeMillis()
        val fallback = ProjectEntity(
            name = DEFAULT_PROJECT_NAME,
            createdAt = now,
            updatedAt = now
        )
        val id = projectDao.insertProject(fallback)
        return projectDao.getProject(id) ?: fallback.copy(id = id)
    }

    suspend fun getProject(projectId: Long): ProjectEntity? {
        return projectDao.getProject(projectId)
    }

    suspend fun createProject(name: String): ProjectEntity {
        val now = System.currentTimeMillis()
        val project = ProjectEntity(
            name = name.trim(),
            createdAt = now,
            updatedAt = now
        )
        val id = projectDao.insertProject(project)
        return projectDao.getProject(id) ?: project.copy(id = id)
    }

    suspend fun renameProject(projectId: Long, name: String): ProjectEntity? {
        val project = projectDao.getProject(projectId) ?: return null
        val updated = project.copy(
            name = name.trim(),
            updatedAt = System.currentTimeMillis()
        )
        projectDao.updateProject(updated)
        return updated
    }

    suspend fun deleteProjectAndFiles(projectId: Long): ProjectEntity? {
        val projects = projectDao.getProjects()
        if (projects.size <= 1) return null

        val nextProject = projects.firstOrNull { it.id != projectId } ?: return null
        val details = expenseDao.getAllExpenseWithAttachments(projectId)
        details.flatMap { it.attachments }.forEach { attachment ->
            runCatching { File(attachment.filePath).delete() }
        }
        expenseDao.deleteExpensesForProject(projectId)
        advanceFundDao.deleteForProject(projectId)
        projectDao.deleteProject(projectId)
        return nextProject
    }

    fun observeAdvanceFund(projectId: Long): Flow<Double> {
        return advanceFundDao.observeAdvanceFund(projectId).map { it?.totalFund ?: 0.0 }
    }

    fun observeTotalSpent(projectId: Long): Flow<Double> = expenseDao.observeTotalSpent(projectId)

    fun observeFilteredExpenses(
        projectId: Long,
        filter: ExpenseFilterUiState
    ): Flow<List<ExpenseEntity>> {
        return expenseDao.observeFilteredExpenses(
            projectId = projectId,
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

    suspend fun setAdvanceFund(projectId: Long, totalFund: Double) {
        advanceFundDao.upsert(
            AdvanceFundEntity(
                projectId = projectId,
                totalFund = totalFund
            )
        )
    }

    suspend fun getExpenseWithAttachments(id: Long): ExpenseWithAttachments? {
        return expenseDao.getExpenseWithAttachments(id)
    }

    suspend fun getAllExpenseWithAttachments(projectId: Long): List<ExpenseWithAttachments> {
        return expenseDao.getAllExpenseWithAttachments(projectId)
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

    companion object {
        const val DEFAULT_PROJECT_ID = 1L
        const val DEFAULT_PROJECT_NAME = "默认项目"
    }
}
