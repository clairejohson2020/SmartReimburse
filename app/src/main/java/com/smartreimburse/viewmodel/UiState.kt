package com.smartreimburse.viewmodel

import com.smartreimburse.data.AttachmentType
import com.smartreimburse.data.ProjectEntity

data class ProjectSelectionUiState(
    val projects: List<ProjectEntity> = emptyList(),
    val currentProjectId: Long? = null,
    val message: String? = null
) {
    val currentProject: ProjectEntity? = projects.firstOrNull { it.id == currentProjectId }
    val canDeleteProject: Boolean = projects.size > 1
}

enum class InvoiceFilter(val label: String) {
    ALL("全部"),
    WITH_INVOICE("有发票"),
    WITHOUT_INVOICE("无发票")
}

data class DashboardUiState(
    val totalFund: Double = 0.0,
    val totalSpent: Double = 0.0
) {
    val remaining: Double = totalFund - totalSpent
    val spentProgress: Float = if (totalFund <= 0.0) {
        0f
    } else {
        (totalSpent / totalFund).toFloat().coerceIn(0f, 1f)
    }
}

data class ExpenseFilterUiState(
    val keyword: String = "",
    val fromDateText: String = "",
    val toDateText: String = "",
    val minAmountText: String = "",
    val maxAmountText: String = "",
    val invoiceFilter: InvoiceFilter = InvoiceFilter.ALL
)

data class AttachmentDraft(
    val id: Long = 0,
    val localId: String,
    val expenseId: Long = 0,
    val type: AttachmentType,
    val filePath: String
)

data class ExpenseFormUiState(
    val id: Long = 0,
    val projectId: Long = 1,
    val name: String = "",
    val model: String = "",
    val quantity: String = "1",
    val price: String = "",
    val totalAmount: String = "",
    val dateMillis: Long = System.currentTimeMillis(),
    val hasInvoice: Boolean = true,
    val invoiceNumber: String = "",
    val onlineLink: String = "",
    val notes: String = "",
    val isReimbursed: Boolean = false,
    val attachments: List<AttachmentDraft> = emptyList(),
    val isSaving: Boolean = false,
    val isOcrRunning: Boolean = false,
    val message: String? = null
) {
    val invoiceAttachments: List<AttachmentDraft> = attachments.filter { it.type == AttachmentType.INVOICE }
    val paymentScreenshots: List<AttachmentDraft> = attachments.filter { it.type == AttachmentType.PAYMENT_SCREENSHOT }
    val receiptAttachments: List<AttachmentDraft> = attachments.filter { it.type == AttachmentType.RECEIPT }
}
