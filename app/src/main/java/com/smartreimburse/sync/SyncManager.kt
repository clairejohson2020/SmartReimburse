package com.smartreimburse.sync

import android.content.Context
import android.os.Build
import androidx.room.withTransaction
import com.smartreimburse.data.AdvanceFundEntity
import com.smartreimburse.data.AppDatabase
import com.smartreimburse.data.AttachmentEntity
import com.smartreimburse.data.AttachmentType
import com.smartreimburse.data.ExpenseEntity
import com.smartreimburse.data.Money
import com.smartreimburse.data.ProjectEntity
import com.smartreimburse.data.SyncState
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class SyncManager(
    context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context)
) {
    private val appContext = context.applicationContext
    private val credentials = SyncCredentialsStore(appContext)
    private val api = SyncApiClient()

    val isConfigured: Boolean get() = api.isConfigured
    val isPaired: Boolean get() = credentials.accessToken != null

    suspend fun createPairing(): PairingInfo {
        check(isConfigured) { "构建时尚未配置统一同步 API 地址" }
        return api.createPairing(
            deviceId = credentials.deviceId,
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        ).also {
            credentials.pairingId = it.pairingId
            credentials.pairingSecret = it.requestSecret
        }
    }

    suspend fun completePairing(): Int {
        val pairingId = checkNotNull(credentials.pairingId) { "请先生成配对码" }
        val secret = checkNotNull(credentials.pairingSecret) { "配对信息已失效，请重新生成" }
        val session = api.exchangePairing(pairingId, secret)
        credentials.accessToken = session.accessToken
        credentials.accountUserId = session.userId
        credentials.pairingId = null
        credentials.pairingSecret = null
        // Pull remote projects before the first push so a same-named empty
        // local default project can be linked instead of duplicated.
        pullEntity(session.accessToken, "projects", ::applyProjects)
        return syncNow()
    }

    suspend fun syncNow(): Int {
        val token = checkNotNull(credentials.accessToken) { "请先与微信小程序配对" }
        pushDeletions(token)
        pushProjects(token)
        pushExpenses(token)
        pullEntity(token, "projects", ::applyProjects)
        pullEntity(token, "expenses", ::applyExpenses)
        pullEntity(token, "attachments", ::applyAttachments)
        pullEntity(token, "tombstones", ::applyTombstones)
        return conflictCount()
    }

    suspend fun keepLocalConflicts(): Int {
        database.withTransaction {
            database.projectDao().getConflicts().forEach { project ->
                database.projectDao().updateProject(
                    project.copy(syncState = SyncState.PENDING, clientMutationId = newMutationId())
                )
            }
            database.expenseDao().getExpenseConflicts().forEach { expense ->
                database.expenseDao().updateExpense(
                    expense.copy(syncState = SyncState.PENDING, clientMutationId = newMutationId())
                )
            }
        }
        return syncNow()
    }

    suspend fun useCloudForConflicts(): Int {
        database.withTransaction {
            database.projectDao().getConflicts().forEach { project ->
                database.projectDao().updateProject(
                    project.copy(syncState = SyncState.SYNCED, clientMutationId = null)
                )
            }
            database.expenseDao().getExpenseConflicts().forEach { expense ->
                database.expenseDao().updateExpense(
                    expense.copy(syncState = SyncState.SYNCED, clientMutationId = null)
                )
            }
        }
        credentials.setCursor("projects", 0)
        credentials.setCursor("expenses", 0)
        return syncNow()
    }

    suspend fun disconnect() {
        credentials.accessToken?.let { api.revokeCurrentToken(it) }
        credentials.clearSession()
    }

    private suspend fun pushDeletions(token: String) {
        val dao = database.localDeletionDao()
        for (deletion in dao.getPending()) {
            val mutation = JSONObject()
                .put("clientMutationId", deletion.clientMutationId)
                .put("entity", deletion.entityType)
                .put("operation", "delete")
                .put("remoteId", deletion.remoteId)
                .put("baseVersion", deletion.baseVersion)
            val result = api.push(token, JSONArray().put(mutation)).getJSONObject(0)
            if (result.optBoolean("ok")) dao.delete(deletion)
            else if (result.optBoolean("conflict")) error(result.optString("message", "删除发生冲突"))
        }
    }

    private suspend fun pushProjects(token: String) {
        val projectDao = database.projectDao()
        for (project in projectDao.getPendingProjects()) {
            val fund = database.advanceFundDao().getAdvanceFund(project.id)
            val mutationId = project.clientMutationId ?: newMutationId()
            val data = JSONObject()
                .put("name", project.name)
                .put("advanceFundCents", fund?.totalFundCents ?: 0L)
            val mutation = JSONObject()
                .put("clientMutationId", mutationId)
                .put("entity", "project")
                .put("operation", "upsert")
                .put("baseVersion", project.syncVersion)
                .put("data", data)
            project.remoteId?.let { mutation.put("remoteId", it) }
            val result = api.push(token, JSONArray().put(mutation)).getJSONObject(0)
            when {
                result.optBoolean("ok") -> projectDao.markSynced(
                    project.id,
                    result.getString("remoteId"),
                    result.getLong("version")
                )
                result.optBoolean("conflict") -> projectDao.markConflict(project.id, project.syncVersion)
                else -> error(result.optString("message", "项目同步失败"))
            }
        }
    }

    private suspend fun pushExpenses(token: String) {
        val expenseDao = database.expenseDao()
        val projectDao = database.projectDao()
        for (expense in expenseDao.getPendingExpenses()) {
            val project = projectDao.getProject(expense.projectId) ?: continue
            val projectRemoteId = project.remoteId ?: continue
            val detail = expenseDao.getExpenseWithAttachments(expense.id) ?: continue
            val syncedAttachments = detail.attachments.map { attachment ->
                if (attachment.cloudFileId != null) attachment
                else {
                    val file = File(attachment.filePath)
                    require(file.isFile) { "附件文件不存在：${file.name}" }
                    val uploaded = api.uploadAttachment(token, file)
                    attachment.copy(
                        cloudFileId = uploaded.getString("fileID"),
                        syncState = SyncState.PENDING
                    ).also { expenseDao.updateAttachment(it) }
                }
            }
            val attachmentJson = JSONArray()
            syncedAttachments.forEach { attachment ->
                attachmentJson.put(
                    JSONObject()
                        .put("_id", attachment.remoteId.orEmpty())
                        .put("fileID", attachment.cloudFileId)
                        .put("fileName", File(attachment.filePath).name)
                        .put("size", File(attachment.filePath).length())
                        .put("type", attachment.type.toRemoteType())
                )
            }
            val data = JSONObject()
                .put("projectId", projectRemoteId)
                .put("name", expense.name)
                .put("model", expense.model)
                .put("quantity", expense.quantity)
                .put("priceCents", expense.priceCents)
                .put("amountCents", expense.amountCents)
                .put("date", expense.date)
                .put("hasInvoice", expense.hasInvoice)
                .put("invoiceNumber", expense.invoiceNumber.orEmpty())
                .put("onlineLink", expense.onlineLink.orEmpty())
                .put("notes", expense.notes.orEmpty())
                .put("isReimbursed", expense.isReimbursed)
                .put("attachments", attachmentJson)
            val mutation = JSONObject()
                .put("clientMutationId", expense.clientMutationId ?: newMutationId())
                .put("entity", "expense")
                .put("operation", "upsert")
                .put("baseVersion", expense.syncVersion)
                .put("data", data)
            expense.remoteId?.let { mutation.put("remoteId", it) }
            val result = api.push(token, JSONArray().put(mutation)).getJSONObject(0)
            when {
                result.optBoolean("ok") -> {
                    expenseDao.markExpenseSynced(
                        expense.id,
                        result.getString("remoteId"),
                        result.getLong("version")
                    )
                    val remoteAttachments = result.optJSONArray("attachments") ?: JSONArray()
                    for (index in 0 until remoteAttachments.length()) {
                        val remote = remoteAttachments.getJSONObject(index)
                        syncedAttachments.firstOrNull { it.cloudFileId == remote.getString("fileID") }?.let {
                            expenseDao.markAttachmentSynced(
                                it.id,
                                remote.getString("remoteId"),
                                remote.getLong("version")
                            )
                        }
                    }
                }
                result.optBoolean("conflict") -> expenseDao.markExpenseConflict(expense.id, expense.syncVersion)
                else -> error(result.optString("message", "支出同步失败"))
            }
        }
    }

    private suspend fun pullEntity(
        token: String,
        entity: String,
        apply: suspend (JSONArray, String) -> Unit
    ) {
        val since = credentials.cursor(entity)
        val until = System.currentTimeMillis()
        var offset = 0
        while (true) {
            val page = api.pull(token, entity, since, until, offset)
            val items = page.getJSONArray("items")
            apply(items, token)
            if (!page.optBoolean("hasMore")) {
                credentials.setCursor(entity, page.getLong("snapshotUntil"))
                return
            }
            offset = page.getInt("nextOffset")
        }
    }

    private suspend fun applyProjects(items: JSONArray, @Suppress("UNUSED_PARAMETER") token: String) {
        val dao = database.projectDao()
        val fundDao = database.advanceFundDao()
        database.withTransaction {
            for (index in 0 until items.length()) {
                val remote = items.getJSONObject(index)
                val remoteId = remote.getString("_id")
                val version = remote.optLong("version", 1L)
                val existing = dao.getProjectByRemoteId(remoteId)
                    ?: dao.getUnlinkedProjectByName(remote.getString("name"))
                if (existing != null && existing.syncState != SyncState.SYNCED && version > existing.syncVersion) {
                    val localFund = fundDao.getAdvanceFund(existing.id)?.totalFundCents ?: 0L
                    val hasLocalData = database.expenseDao().countExpensesForProject(existing.id) > 0 || localFund != 0L
                    if (existing.remoteId == null && hasLocalData) {
                        dao.updateProject(
                            existing.copy(
                                remoteId = remoteId,
                                syncVersion = version,
                                syncState = SyncState.PENDING,
                                clientMutationId = existing.clientMutationId ?: newMutationId()
                            )
                        )
                        continue
                    }
                    if (existing.remoteId != null) {
                        dao.markConflict(existing.id, version)
                        continue
                    }
                }
                val now = remote.optLong("updatedAt", System.currentTimeMillis())
                val entity = ProjectEntity(
                    id = existing?.id ?: 0,
                    name = remote.getString("name"),
                    createdAt = remote.optLong("createdAt", now),
                    updatedAt = now,
                    remoteId = remoteId,
                    syncVersion = version,
                    syncState = SyncState.SYNCED
                )
                val localId = if (existing == null) dao.insertProject(entity) else existing.id.also { dao.updateProject(entity) }
                val cents = remote.optLong("advanceFundCents", Money.fromDouble(remote.optDouble("advanceFund", 0.0)))
                fundDao.upsert(AdvanceFundEntity(localId, Money.toDouble(cents), cents))
            }
        }
    }

    private suspend fun applyExpenses(items: JSONArray, @Suppress("UNUSED_PARAMETER") token: String) {
        val expenseDao = database.expenseDao()
        val projectDao = database.projectDao()
        database.withTransaction {
            for (index in 0 until items.length()) {
                val remote = items.getJSONObject(index)
                val project = projectDao.getProjectByRemoteId(remote.getString("projectId")) ?: continue
                val remoteId = remote.getString("_id")
                val version = remote.optLong("version", 1L)
                val existing = expenseDao.getExpenseByRemoteId(remoteId)
                if (existing != null && existing.syncState != SyncState.SYNCED && version > existing.syncVersion) {
                    expenseDao.markExpenseConflict(existing.id, version)
                    continue
                }
                val priceCents = remote.optLong("priceCents", Money.fromDouble(remote.optDouble("price", 0.0)))
                val amountCents = remote.optLong("amountCents", Money.fromDouble(remote.optDouble("totalAmount", 0.0)))
                val entity = ExpenseEntity(
                    id = existing?.id ?: 0,
                    projectId = project.id,
                    name = remote.getString("name"),
                    model = remote.optString("model"),
                    quantity = remote.optInt("quantity", 1),
                    price = Money.toDouble(priceCents),
                    totalAmount = Money.toDouble(amountCents),
                    priceCents = priceCents,
                    amountCents = amountCents,
                    date = remote.optLong("date", System.currentTimeMillis()),
                    hasInvoice = remote.optBoolean("hasInvoice"),
                    invoiceNumber = remote.optString("invoiceNumber").ifBlank { null },
                    onlineLink = remote.optString("onlineLink").ifBlank { null },
                    notes = remote.optString("notes").ifBlank { null },
                    isReimbursed = remote.optBoolean("isReimbursed"),
                    remoteId = remoteId,
                    syncVersion = version,
                    syncState = SyncState.SYNCED
                )
                if (existing == null) expenseDao.insertExpense(entity) else expenseDao.updateExpense(entity)
            }
        }
    }

    private suspend fun applyAttachments(items: JSONArray, token: String) {
        val dao = database.expenseDao()
        for (index in 0 until items.length()) {
            val remote = items.getJSONObject(index)
            val expense = dao.getExpenseByRemoteId(remote.getString("expenseId")) ?: continue
            val remoteId = remote.getString("_id")
            val cloudFileId = remote.getString("fileID")
            val existing = dao.getAttachmentByRemoteId(remoteId)
            val file = existing?.filePath?.let(::File)?.takeIf { it.isFile }
                ?: File(appContext.filesDir, "attachments/synced/$remoteId.jpg").also { destination ->
                    api.download(api.attachmentUrl(token, cloudFileId), destination)
                }
            val entity = AttachmentEntity(
                id = existing?.id ?: 0,
                expenseId = expense.id,
                type = remote.optString("type").toAttachmentType(),
                filePath = file.absolutePath,
                remoteId = remoteId,
                cloudFileId = cloudFileId,
                syncVersion = remote.optLong("version", 1L),
                syncState = SyncState.SYNCED
            )
            if (existing == null) dao.insertAttachments(listOf(entity)) else dao.updateAttachment(entity)
        }
    }

    private suspend fun applyTombstones(items: JSONArray, @Suppress("UNUSED_PARAMETER") token: String) {
        val projectDao = database.projectDao()
        val expenseDao = database.expenseDao()
        database.withTransaction {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val remoteId = item.getString("entityId")
                when (item.getString("entityType")) {
                    "project" -> projectDao.getProjectByRemoteId(remoteId)?.let {
                        expenseDao.getAllExpenseWithAttachments(it.id)
                            .flatMap { detail -> detail.attachments }
                            .forEach { attachment -> File(attachment.filePath).delete() }
                        expenseDao.deleteExpensesForProject(it.id)
                        database.advanceFundDao().deleteForProject(it.id)
                        projectDao.deleteByRemoteId(remoteId)
                    }
                    "expense" -> expenseDao.getExpenseByRemoteId(remoteId)?.let {
                        expenseDao.getAttachmentsForExpense(it.id)
                            .forEach { attachment -> File(attachment.filePath).delete() }
                        expenseDao.deleteExpenseByRemoteId(remoteId)
                    }
                    "attachment" -> expenseDao.getAttachmentByRemoteId(remoteId)?.let {
                        if (it.syncState != SyncState.PENDING) {
                            File(it.filePath).delete()
                            expenseDao.deleteAttachmentByRemoteId(remoteId)
                        }
                    }
                }
            }
        }
    }

    private fun AttachmentType.toRemoteType(): String = when (this) {
        AttachmentType.INVOICE -> "invoice"
        AttachmentType.PAYMENT_SCREENSHOT -> "payment"
        AttachmentType.RECEIPT -> "receipt"
    }

    private fun String.toAttachmentType(): AttachmentType = when (this) {
        "invoice" -> AttachmentType.INVOICE
        "payment" -> AttachmentType.PAYMENT_SCREENSHOT
        else -> AttachmentType.RECEIPT
    }

    private fun newMutationId(): String = UUID.randomUUID().toString().replace("-", "_")

    private suspend fun conflictCount(): Int =
        database.projectDao().getConflicts().size + database.expenseDao().getExpenseConflicts().size
}
