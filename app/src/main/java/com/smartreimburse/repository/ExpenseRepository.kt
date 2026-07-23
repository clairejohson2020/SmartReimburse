package com.smartreimburse.repository

import com.smartreimburse.data.AdvanceFundDao
import com.smartreimburse.data.AppDatabase
import com.smartreimburse.data.AdvanceFundEntity
import com.smartreimburse.data.AttachmentEntity
import com.smartreimburse.data.ExpenseDao
import com.smartreimburse.data.ExpenseEntity
import com.smartreimburse.data.ExpenseWithAttachments
import com.smartreimburse.data.ProjectDao
import com.smartreimburse.data.ProjectEntity
import com.smartreimburse.data.Money
import com.smartreimburse.data.SyncState
import com.smartreimburse.data.LocalDeletionDao
import com.smartreimburse.data.LocalDeletionEntity
import java.util.UUID
import androidx.room.withTransaction
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
    private val database: AppDatabase,
    private val projectDao: ProjectDao,
    private val expenseDao: ExpenseDao,
    private val advanceFundDao: AdvanceFundDao,
    private val localDeletionDao: LocalDeletionDao
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
            updatedAt = now,
            clientMutationId = newMutationId()
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
            updatedAt = now,
            clientMutationId = newMutationId()
        )
        val id = projectDao.insertProject(project)
        return projectDao.getProject(id) ?: project.copy(id = id)
    }

    suspend fun renameProject(projectId: Long, name: String): ProjectEntity? {
        val project = projectDao.getProject(projectId) ?: return null
        val updated = project.copy(
            name = name.trim(),
            updatedAt = System.currentTimeMillis(),
            syncState = SyncState.PENDING,
            clientMutationId = newMutationId()
        )
        projectDao.updateProject(updated)
        return updated
    }

    suspend fun deleteProjectAndFiles(projectId: Long): ProjectEntity? {
        val projects = projectDao.getProjects()
        if (projects.size <= 1) return null

        val nextProject = projects.firstOrNull { it.id != projectId } ?: return null
        val project = projects.firstOrNull { it.id == projectId } ?: return null
        val details = expenseDao.getAllExpenseWithAttachments(projectId)
        database.withTransaction {
            project.remoteId?.let { remoteId ->
                localDeletionDao.insert(
                    LocalDeletionEntity(
                        entityType = "project",
                        remoteId = remoteId,
                        baseVersion = project.syncVersion,
                        clientMutationId = newMutationId(),
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            expenseDao.deleteExpensesForProject(projectId)
            advanceFundDao.deleteForProject(projectId)
            projectDao.deleteProject(projectId)
        }
        details.flatMap { it.attachments }.forEach { attachment ->
            runCatching { File(attachment.filePath).delete() }
        }
        return nextProject
    }

    fun observeAdvanceFund(projectId: Long): Flow<Double> {
        return advanceFundDao.observeAdvanceFund(projectId).map {
            it?.let { fund -> Money.toDouble(fund.totalFundCents) } ?: 0.0
        }
    }

    fun observeTotalSpent(projectId: Long): Flow<Double> =
        expenseDao.observeTotalSpentCents(projectId).map(Money::toDouble)

    fun observeFilteredExpenses(
        projectId: Long,
        filter: ExpenseFilterUiState
    ): Flow<List<ExpenseEntity>> {
        return expenseDao.observeFilteredExpenses(
            projectId = projectId,
            keyword = filter.keyword.trim(),
            fromDate = filter.fromDateText.toStartOfDayMillisOrNull(),
            toDate = filter.toDateText.toEndOfDayMillisOrNull(),
            minAmountCents = Money.parseCents(filter.minAmountText),
            maxAmountCents = Money.parseCents(filter.maxAmountText),
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
        val cents = Money.fromDouble(totalFund)
        advanceFundDao.upsert(
            AdvanceFundEntity(
                projectId = projectId,
                totalFund = Money.toDouble(cents),
                totalFundCents = cents
            )
        )
        projectDao.getProject(projectId)?.let { project ->
            projectDao.updateProject(
                project.copy(
                    updatedAt = System.currentTimeMillis(),
                    syncState = SyncState.PENDING,
                    clientMutationId = newMutationId()
                )
            )
        }
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
            expenseDao.insertExpenseWithAttachments(
                expense.copy(clientMutationId = newMutationId()),
                attachments.map { it.copy(syncState = SyncState.PENDING) }
            )
        } else {
            val existingExpense = expenseDao.getExpense(expense.id)
            val persistedExpense = expense.copy(
                remoteId = existingExpense?.remoteId,
                syncVersion = existingExpense?.syncVersion ?: 0,
                syncState = SyncState.PENDING,
                clientMutationId = newMutationId()
            )
            val existingAttachments = expenseDao.getAttachmentsForExpense(expense.id)
                .associateBy { it.id }
            val persistedAttachments = attachments.map { attachment ->
                val existing = existingAttachments[attachment.id]
                attachment.copy(
                    remoteId = existing?.remoteId,
                    cloudFileId = existing?.cloudFileId,
                    syncVersion = existing?.syncVersion ?: 0,
                    syncState = SyncState.PENDING
                )
            }
            val retainedPaths = attachments.map { it.filePath }.toSet()
            val removedFiles = existingAttachments.values.filterNot { it.filePath in retainedPaths }
            expenseDao.updateExpenseWithAttachments(persistedExpense, persistedAttachments)
            removedFiles.forEach { removed -> runCatching { File(removed.filePath).delete() } }
            expense.id
        }
    }

    suspend fun deleteExpenseAndFiles(expenseId: Long) {
        val detail = expenseDao.getExpenseWithAttachments(expenseId) ?: return
        database.withTransaction {
            detail.expense.remoteId?.let { remoteId ->
                localDeletionDao.insert(
                    LocalDeletionEntity(
                        entityType = "expense",
                        remoteId = remoteId,
                        baseVersion = detail.expense.syncVersion,
                        clientMutationId = newMutationId(),
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            expenseDao.deleteExpense(detail.expense)
        }
        detail.attachments.forEach { attachment ->
            runCatching { File(attachment.filePath).delete() }
        }
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

    private fun newMutationId(): String = UUID.randomUUID().toString().replace("-", "_")
}
