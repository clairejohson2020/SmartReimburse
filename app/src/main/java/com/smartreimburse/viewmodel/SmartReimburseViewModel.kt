package com.smartreimburse.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartreimburse.camera.AttachmentStore
import com.smartreimburse.data.AppDatabase
import com.smartreimburse.data.AttachmentEntity
import com.smartreimburse.data.AttachmentType
import com.smartreimburse.data.ExpenseEntity
import com.smartreimburse.data.ExpenseWithAttachments
import com.smartreimburse.export.ExcelExporter
import com.smartreimburse.ocr.OcrParser
import com.smartreimburse.ocr.TextRecognitionService
import com.smartreimburse.repository.ExpenseRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class SmartReimburseViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val repository = ExpenseRepository(
        expenseDao = database.expenseDao(),
        advanceFundDao = database.advanceFundDao()
    )
    private val attachmentStore = AttachmentStore(application)
    private val textRecognitionService = TextRecognitionService(application)
    private val ocrParser = OcrParser()
    private val excelExporter = ExcelExporter(application)

    private val _filterState = MutableStateFlow(ExpenseFilterUiState())
    val filterState: StateFlow<ExpenseFilterUiState> = _filterState

    private val _formState = MutableStateFlow(ExpenseFormUiState())
    val formState: StateFlow<ExpenseFormUiState> = _formState

    val dashboardState: StateFlow<DashboardUiState> = combine(
        repository.observeAdvanceFund(),
        repository.observeTotalSpent()
    ) { totalFund, totalSpent ->
        DashboardUiState(totalFund = totalFund, totalSpent = totalSpent)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState()
    )

    val expenses = _filterState
        .flatMapLatest { repository.observeFilteredExpenses(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun observeExpenseDetail(id: Long): Flow<ExpenseWithAttachments?> {
        return repository.observeExpenseWithAttachments(id)
    }

    fun setAdvanceFund(value: String) {
        val amount = value.toDoubleOrNull()
        if (amount == null || amount < 0.0) {
            _formState.update { it.copy(message = "请输入有效的备用金金额") }
            return
        }
        viewModelScope.launch {
            repository.setAdvanceFund(amount)
        }
    }

    fun updateFilter(transform: (ExpenseFilterUiState) -> ExpenseFilterUiState) {
        _filterState.update(transform)
    }

    fun startNewExpense() {
        _formState.value = ExpenseFormUiState()
    }

    fun loadExpenseForEdit(expenseId: Long) {
        viewModelScope.launch {
            val detail = repository.getExpenseWithAttachments(expenseId) ?: return@launch
            val expense = detail.expense
            _formState.value = ExpenseFormUiState(
                id = expense.id,
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
                val price = state.price.toDoubleOrNull() ?: 0.0
                val totalAmount = state.totalAmount.toDoubleOrNull() ?: price * quantity
                val expense = ExpenseEntity(
                    id = state.id,
                    name = state.name.trim(),
                    model = state.model.trim(),
                    quantity = quantity,
                    price = price,
                    totalAmount = totalAmount,
                    date = state.dateMillis,
                    hasInvoice = state.hasInvoice,
                    invoiceNumber = state.invoiceNumber.trim().ifBlank { null },
                    onlineLink = state.onlineLink.trim().ifBlank { null },
                    notes = state.notes.trim().ifBlank { null },
                    isReimbursed = state.isReimbursed
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
                val details = withContext(Dispatchers.IO) {
                    repository.getAllExpenseWithAttachments()
                }
                withContext(Dispatchers.IO) {
                    excelExporter.export(details)
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
}
