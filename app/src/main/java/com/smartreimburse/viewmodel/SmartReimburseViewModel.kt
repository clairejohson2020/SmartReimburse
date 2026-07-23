package com.smartreimburse.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartreimburse.camera.AttachmentStore
import com.smartreimburse.data.AppDatabase
import com.smartreimburse.data.AttachmentEntity
import com.smartreimburse.data.AttachmentType
import com.smartreimburse.data.ExpenseEntity
import com.smartreimburse.data.ExpenseWithAttachments
import com.smartreimburse.data.Money
import com.smartreimburse.data.SyncState
import com.smartreimburse.export.ExcelExporter
import com.smartreimburse.ocr.OcrParser
import com.smartreimburse.ocr.TextRecognitionService
import com.smartreimburse.repository.ExpenseRepository
import com.smartreimburse.sync.SyncManager
import com.smartreimburse.repository.ExpenseRepository.Companion.DEFAULT_PROJECT_ID
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class SmartReimburseViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val repository = ExpenseRepository(
        database = database,
        projectDao = database.projectDao(),
        expenseDao = database.expenseDao(),
        advanceFundDao = database.advanceFundDao(),
        localDeletionDao = database.localDeletionDao()
    )
    private val attachmentStore = AttachmentStore(application)
    private val textRecognitionService = TextRecognitionService(application)
    private val ocrParser = OcrParser()
    private val excelExporter = ExcelExporter(application)
    private val syncManager = SyncManager(application, database)

    private val _filterState = MutableStateFlow(ExpenseFilterUiState())
    val filterState: StateFlow<ExpenseFilterUiState> = _filterState

    private val _formState = MutableStateFlow(ExpenseFormUiState())
    val formState: StateFlow<ExpenseFormUiState> = _formState

    private val _currentProjectId = MutableStateFlow<Long?>(null)
    private val _projectMessage = MutableStateFlow<String?>(null)
    private val _syncState = MutableStateFlow(
        SyncUiState(
            isConfigured = syncManager.isConfigured,
            isPaired = syncManager.isPaired
        )
    )
    val syncState: StateFlow<SyncUiState> = _syncState

    val projectState: StateFlow<ProjectSelectionUiState> = combine(
        repository.observeProjects(),
        _currentProjectId,
        _projectMessage
    ) { projects, currentProjectId, message ->
        val resolvedProjectId = currentProjectId
            ?.takeIf { id -> projects.any { it.id == id } }
            ?: projects.firstOrNull()?.id
        ProjectSelectionUiState(
            projects = projects,
            currentProjectId = resolvedProjectId,
            message = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProjectSelectionUiState()
    )

    val dashboardState: StateFlow<DashboardUiState> = _currentProjectId
        .filterNotNull()
        .flatMapLatest { projectId ->
            combine(
                repository.observeAdvanceFund(projectId),
                repository.observeTotalSpent(projectId)
            ) { totalFund, totalSpent ->
                DashboardUiState(totalFund = totalFund, totalSpent = totalSpent)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DashboardUiState()
        )

    val expenses = combine(
        _filterState,
        _currentProjectId.filterNotNull()
    ) { filter, projectId ->
        projectId to filter
    }
        .flatMapLatest { (projectId, filter) -> repository.observeFilteredExpenses(projectId, filter) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            val savedProjectId = preferences
                .getLong(KEY_CURRENT_PROJECT_ID, 0L)
                .takeIf { it > 0L }
            val project = repository.ensureInitialProject(savedProjectId)
            selectProjectInternal(project.id)
        }
    }

    fun observeExpenseDetail(id: Long): Flow<ExpenseWithAttachments?> {
        return repository.observeExpenseWithAttachments(id)
    }

    fun selectProject(projectId: Long) {
        viewModelScope.launch {
            if (repository.getProject(projectId) != null) {
                selectProjectInternal(projectId, resetFilter = true)
            }
        }
    }

    fun createProject(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _projectMessage.value = "请输入项目名称"
            return
        }

        viewModelScope.launch {
            runCatching {
                repository.createProject(trimmed)
            }.onSuccess { project ->
                selectProjectInternal(project.id, resetFilter = true)
                _projectMessage.value = "项目已创建"
            }.onFailure { throwable ->
                _projectMessage.value = throwable.message ?: "项目创建失败"
            }
        }
    }

    fun renameProject(projectId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _projectMessage.value = "请输入项目名称"
            return
        }

        viewModelScope.launch {
            runCatching {
                repository.renameProject(projectId, trimmed)
            }.onSuccess { updated ->
                if (updated == null) {
                    _projectMessage.value = "项目不存在"
                } else {
                    _projectMessage.value = "项目已重命名"
                }
            }.onFailure { throwable ->
                _projectMessage.value = throwable.message ?: "项目重命名失败"
            }
        }
    }

    fun deleteProject(projectId: Long) {
        viewModelScope.launch {
            runCatching {
                repository.deleteProjectAndFiles(projectId)
            }.onSuccess { nextProject ->
                if (nextProject == null) {
                    _projectMessage.value = "至少保留一个项目"
                    return@onSuccess
                }
                if (_currentProjectId.value == projectId) {
                    selectProjectInternal(nextProject.id, resetFilter = true)
                }
                _projectMessage.value = "项目已删除"
            }.onFailure { throwable ->
                _projectMessage.value = throwable.message ?: "项目删除失败"
            }
        }
    }

    fun dismissProjectMessage() {
        _projectMessage.value = null
    }

    fun createSyncPairing() {
        viewModelScope.launch {
            _syncState.update { it.copy(isBusy = true, message = null) }
            runCatching { syncManager.createPairing() }
                .onSuccess { pairing ->
                    _syncState.update {
                        it.copy(
                            isBusy = false,
                            pairingCode = pairing.code,
                            pairingExpiresAt = pairing.expiresAt,
                            message = "请在微信小程序中输入配对码并批准"
                        )
                    }
                }
                .onFailure { error ->
                    _syncState.update { it.copy(isBusy = false, message = error.message ?: "配对失败") }
                }
        }
    }

    fun completeSyncPairing() {
        viewModelScope.launch {
            _syncState.update { it.copy(isBusy = true, message = "正在确认配对并首次同步...") }
            runCatching { syncManager.completePairing() }
                .onSuccess { conflicts ->
                    _syncState.update {
                        it.copy(
                            isBusy = false,
                            isPaired = true,
                            pairingCode = null,
                            pairingExpiresAt = null,
                            conflictCount = conflicts,
                            lastSyncAt = System.currentTimeMillis(),
                            message = "配对和首次同步已完成"
                        )
                    }
                }
                .onFailure { error ->
                    _syncState.update { it.copy(isBusy = false, message = error.message ?: "配对确认失败") }
                }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _syncState.update { it.copy(isBusy = true, message = "正在同步...") }
            runCatching { syncManager.syncNow() }
                .onSuccess { conflicts ->
                    _syncState.update {
                        it.copy(
                            isBusy = false,
                            conflictCount = conflicts,
                            lastSyncAt = System.currentTimeMillis(),
                            message = if (conflicts == 0) "同步完成" else "发现 $conflicts 条并发修改，请选择保留版本"
                        )
                    }
                }
                .onFailure { error ->
                    _syncState.update { it.copy(isBusy = false, message = error.message ?: "同步失败") }
                }
        }
    }

    fun disconnectSync() {
        viewModelScope.launch {
            _syncState.update { it.copy(isBusy = true, message = "正在撤销本机连接...") }
            runCatching { syncManager.disconnect() }
                .onSuccess {
                    _syncState.value = SyncUiState(
                        isConfigured = syncManager.isConfigured,
                        message = "已撤销设备令牌并解除本机同步"
                    )
                }
                .onFailure { error ->
                    _syncState.update {
                        it.copy(
                            isBusy = false,
                            message = error.message ?: "撤销失败，请联网后重试"
                        )
                    }
                }
        }
    }

    fun keepLocalSyncConflicts() {
        resolveSyncConflicts("正在以本机版本解决冲突...", syncManager::keepLocalConflicts)
    }

    fun useCloudSyncConflicts() {
        resolveSyncConflicts("正在采用云端版本...", syncManager::useCloudForConflicts)
    }

    private fun resolveSyncConflicts(message: String, resolver: suspend () -> Int) {
        viewModelScope.launch {
            _syncState.update { it.copy(isBusy = true, message = message) }
            runCatching { resolver() }
                .onSuccess { conflicts ->
                    _syncState.update {
                        it.copy(
                            isBusy = false,
                            conflictCount = conflicts,
                            lastSyncAt = System.currentTimeMillis(),
                            message = if (conflicts == 0) "冲突已解决并同步" else "仍有 $conflicts 条冲突"
                        )
                    }
                }
                .onFailure { error ->
                    _syncState.update { it.copy(isBusy = false, message = error.message ?: "冲突处理失败") }
                }
        }
    }

    fun dismissSyncMessage() {
        _syncState.update { it.copy(message = null) }
    }

    fun setAdvanceFund(value: String) {
        val amount = value.toDoubleOrNull()
        if (amount == null || amount < 0.0) {
            _formState.update { it.copy(message = "请输入有效的备用金金额") }
            return
        }
        viewModelScope.launch {
            repository.setAdvanceFund(activeProjectId(), amount)
        }
    }

    fun updateFilter(transform: (ExpenseFilterUiState) -> ExpenseFilterUiState) {
        _filterState.update(transform)
    }

    fun startNewExpense() {
        _formState.value = ExpenseFormUiState(projectId = activeProjectId())
    }

    fun loadExpenseForEdit(expenseId: Long) {
        viewModelScope.launch {
            val detail = repository.getExpenseWithAttachments(expenseId) ?: return@launch
            val expense = detail.expense
            _formState.value = ExpenseFormUiState(
                id = expense.id,
                projectId = expense.projectId,
                name = expense.name,
                model = expense.model,
                quantity = expense.quantity.toString(),
                price = trimMoney(expense.price),
                totalAmount = trimMoney(expense.totalAmount),
                dateMillis = expense.date,
                hasInvoice = expense.hasInvoice,
                invoiceNumber = expense.invoiceNumber.orEmpty(),
                onlineLink = expense.onlineLink.orEmpty(),
                notes = expense.notes.orEmpty(),
                isReimbursed = expense.isReimbursed,
                attachments = detail.attachments.map {
                    AttachmentDraft(
                        id = it.id,
                        localId = UUID.randomUUID().toString(),
                        expenseId = it.expenseId,
                        type = it.type,
                        filePath = it.filePath
                    )
                }
            )
        }
    }

    fun updateName(value: String) = _formState.update { it.copy(name = value) }
    fun updateModel(value: String) = _formState.update { it.copy(model = value) }
    fun updateInvoiceNumber(value: String) = _formState.update { it.copy(invoiceNumber = value) }
    fun updateOnlineLink(value: String) = _formState.update { it.copy(onlineLink = value) }
    fun updateNotes(value: String) = _formState.update { it.copy(notes = value) }
    fun updateReimbursed(value: Boolean) = _formState.update { it.copy(isReimbursed = value) }
    fun dismissMessage() = _formState.update { it.copy(message = null) }

    fun updateHasInvoice(value: Boolean) {
        _formState.update {
            it.copy(
                hasInvoice = value,
                onlineLink = if (value) "" else it.onlineLink
            )
        }
    }

    fun updateQuantity(value: String) {
        _formState.update {
            val updated = it.copy(quantity = value.filter { char -> char.isDigit() }.ifBlank { "" })
            updated.recalculateTotal()
        }
    }

    fun updatePrice(value: String) {
        _formState.update {
            it.copy(price = value.cleanMoneyInput()).recalculateTotal()
        }
    }

    fun updateTotalAmount(value: String) {
        _formState.update { it.copy(totalAmount = value.cleanMoneyInput()) }
    }

    fun removeAttachment(localId: String) {
        _formState.update { state ->
            val target = state.attachments.firstOrNull { it.localId == localId }
            if (target?.id == 0L) {
                attachmentStore.deleteFile(target.filePath)
            }
            state.copy(attachments = state.attachments.filterNot { it.localId == localId })
        }
    }

    fun onCapturedImage(path: String, type: AttachmentType, runOcr: Boolean) {
        addAttachmentPath(path, type, runOcr)
    }

    fun onImagesSelected(uris: List<Uri>, type: AttachmentType, runOcr: Boolean) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                uris.map { uri ->
                    withContext(Dispatchers.IO) {
                        attachmentStore.copyImageUriToPrivateStorage(uri, type)
                    }
                }
            }.onSuccess { paths ->
                paths.forEach { path -> addAttachmentPath(path, type, runOcr) }
            }.onFailure { throwable ->
                _formState.update { it.copy(message = throwable.message ?: "图片导入失败") }
            }
        }
    }

    fun saveExpense(onSaved: (Long) -> Unit) {
        val state = _formState.value
        val validationMessage = validateForm(state)
        if (validationMessage != null) {
            _formState.update { it.copy(message = validationMessage) }
            return
        }

        viewModelScope.launch {
            _formState.update { it.copy(isSaving = true, message = null) }
            runCatching {
                val quantity = state.quantity.toIntOrNull() ?: 1
                val priceCents = Money.parseCents(state.price) ?: 0L
                val totalAmountCents = Money.parseCents(state.totalAmount) ?: priceCents * quantity
                val price = Money.toDouble(priceCents)
                val totalAmount = Money.toDouble(totalAmountCents)
                val expense = ExpenseEntity(
                    id = state.id,
                    projectId = state.projectId,
                    name = state.name.trim(),
                    model = state.model.trim(),
                    quantity = quantity,
                    price = price,
                    totalAmount = totalAmount,
                    priceCents = priceCents,
                    amountCents = totalAmountCents,
                    date = state.dateMillis,
                    hasInvoice = state.hasInvoice,
                    invoiceNumber = state.invoiceNumber.trim().ifBlank { null },
                    onlineLink = state.onlineLink.trim().ifBlank { null },
                    notes = state.notes.trim().ifBlank { null },
                    isReimbursed = state.isReimbursed,
                    syncState = SyncState.PENDING,
                    clientMutationId = state.id.takeIf { it > 0 }?.let { "expense_${it}_${System.currentTimeMillis()}" }
                )
                val attachments = state.attachments.map {
                    AttachmentEntity(
                        id = it.id,
                        expenseId = state.id,
                        type = it.type,
                        filePath = it.filePath
                    )
                }
                repository.saveExpense(expense, attachments)
            }.onSuccess { savedId ->
                _formState.update { it.copy(isSaving = false, message = "保存成功") }
                onSaved(savedId)
            }.onFailure { throwable ->
                _formState.update {
                    it.copy(isSaving = false, message = throwable.message ?: "保存失败")
                }
            }
        }
    }

    fun deleteExpense(expenseId: Long, onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteExpenseAndFiles(expenseId)
            onDeleted()
        }
    }

    fun exportExcel(onReady: (File) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val projectId = _currentProjectId.value ?: error("请先创建项目")
                val project = repository.getProject(projectId) ?: error("当前项目不存在")
                val details = withContext(Dispatchers.IO) {
                    repository.getAllExpenseWithAttachments(projectId)
                }
                withContext(Dispatchers.IO) {
                    excelExporter.export(project.name, details)
                }
            }.onSuccess(onReady)
                .onFailure { throwable ->
                    _formState.update { it.copy(message = throwable.message ?: "Excel 导出失败") }
                }
        }
    }

    fun prepareExpenseShare(expenseId: Long, onReady: (ExpenseWithAttachments) -> Unit) {
        viewModelScope.launch {
            repository.getExpenseWithAttachments(expenseId)?.let(onReady)
        }
    }

    private fun addAttachmentPath(path: String, type: AttachmentType, runOcr: Boolean) {
        _formState.update { state ->
            state.copy(
                attachments = state.attachments + AttachmentDraft(
                    localId = UUID.randomUUID().toString(),
                    type = type,
                    filePath = path
                )
            )
        }
        if (runOcr) {
            runOcrForImage(path, type)
        }
    }

    private fun activeProjectId(): Long {
        return _currentProjectId.value ?: DEFAULT_PROJECT_ID
    }

    private fun selectProjectInternal(projectId: Long, resetFilter: Boolean = false) {
        _currentProjectId.value = projectId
        preferences.edit()
            .putLong(KEY_CURRENT_PROJECT_ID, projectId)
            .apply()
        if (resetFilter) {
            _filterState.value = ExpenseFilterUiState()
        }
        _projectMessage.value = null
    }

    private fun runOcrForImage(path: String, type: AttachmentType) {
        viewModelScope.launch {
            _formState.update { it.copy(isOcrRunning = true, message = "正在识别图片文字...") }
            runCatching {
                withContext(Dispatchers.Default) {
                    textRecognitionService.recognizeText(path)
                }
            }.onSuccess { text ->
                applyOcrText(text, type)
            }.onFailure { throwable ->
                _formState.update {
                    it.copy(
                        isOcrRunning = false,
                        message = throwable.message ?: "OCR 识别失败，可手动填写"
                    )
                }
            }
        }
    }

    private fun applyOcrText(text: String, type: AttachmentType) {
        val parsed = ocrParser.parseExpense(text)
        val invoiceNumber = ocrParser.extractInvoiceNumber(text)

        _formState.update { state ->
            state.copy(
                name = state.name.ifBlank { parsed.name.orEmpty() },
                model = state.model.ifBlank { parsed.model.orEmpty() },
                quantity = if (state.quantity.isBlank() || state.quantity == "1") {
                    parsed.quantity?.toString() ?: state.quantity
                } else {
                    state.quantity
                },
                price = state.price.ifBlank { parsed.unitPrice?.let(::trimMoney).orEmpty() },
                totalAmount = state.totalAmount.ifBlank { parsed.totalAmount?.let(::trimMoney).orEmpty() },
                invoiceNumber = if (type == AttachmentType.INVOICE) {
                    state.invoiceNumber.ifBlank { invoiceNumber.orEmpty() }
                } else {
                    state.invoiceNumber
                },
                isOcrRunning = false,
                message = "OCR 已填入可识别字段，请核对后保存"
            )
        }
    }

    private fun validateForm(state: ExpenseFormUiState): String? {
        val totalAmount = state.totalAmount.toDoubleOrNull()
        return when {
            state.name.isBlank() -> "请填写名称"
            state.quantity.toIntOrNull() == null || (state.quantity.toIntOrNull() ?: 0) <= 0 -> "请填写有效数量"
            totalAmount == null || totalAmount <= 0.0 -> "请填写有效金额"
            state.attachments.any { File(it.filePath).length() > MAX_ATTACHMENT_BYTES } -> "单个附件不能超过 5MB"
            state.hasInvoice && state.invoiceAttachments.isEmpty() -> "有发票记录需要上传发票原图"
            !state.hasInvoice && state.paymentScreenshots.isEmpty() -> "无发票记录必须上传付款截图"
            else -> null
        }
    }

    private fun ExpenseFormUiState.recalculateTotal(): ExpenseFormUiState {
        val quantityValue = quantity.toIntOrNull()
        val priceValue = price.toDoubleOrNull()
        return if (quantityValue != null && priceValue != null) {
            copy(totalAmount = trimMoney(quantityValue * priceValue))
        } else {
            this
        }
    }

    private fun String.cleanMoneyInput(): String {
        val builder = StringBuilder()
        var dotSeen = false
        forEach { char ->
            when {
                char.isDigit() -> builder.append(char)
                char == '.' && !dotSeen -> {
                    builder.append(char)
                    dotSeen = true
                }
            }
        }
        return builder.toString()
    }

    private fun trimMoney(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            "%.2f".format(value)
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "smart_reimburse_preferences"
        private const val KEY_CURRENT_PROJECT_ID = "current_project_id"
        private const val MAX_ATTACHMENT_BYTES = 5L * 1024L * 1024L
    }
}
