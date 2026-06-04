package com.smartreimburse.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import com.smartreimburse.data.ExpenseWithAttachments
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ShareManager {
    private const val EXCEL_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private val currencyFormatter: NumberFormat = NumberFormat.getCurrencyInstance(Locale.CHINA)
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun shareExcel(context: Context, file: File) {
        val uri = file.toContentUri(context)
        val intent = ShareCompat.IntentBuilder(context)
            .setType(EXCEL_MIME)
            .setSubject("SmartReimburse 报销明细")
            .setText("SmartReimburse 导出的报销明细 Excel")
            .setStream(uri)
            .intent
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        context.startShareChooser(intent, "分享报销 Excel")
    }

    fun shareExpense(context: Context, detail: ExpenseWithAttachments) {
        val text = detail.toShareText()
        val attachmentUris = detail.attachments
            .mapNotNull { runCatching { File(it.filePath).toContentUri(context) }.getOrNull() }
            .toCollection(ArrayList<Uri>())

        val intent = if (attachmentUris.isEmpty()) {
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachmentUris)
                .putExtra(Intent.EXTRA_TEXT, text)
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        context.startShareChooser(intent, "分享报销记录")
    }

    private fun ExpenseWithAttachments.toShareText(): String {
        val item = this.expense
        val invoiceText = if (item.hasInvoice) {
            "有发票 ${item.invoiceNumber.orEmpty()}".trim()
        } else {
            "无发票"
        }
        val attachmentSummary = attachments
            .groupBy { it.type }
            .entries
            .joinToString("；") { (type, items) -> "${type.label} ${items.size} 张" }
            .ifBlank { "无附件" }

        return buildString {
            appendLine("SmartReimburse 报销记录")
            appendLine("名称：${item.name}")
            appendLine("型号：${item.model}")
            appendLine("数量：${item.quantity}")
            appendLine("金额：${currencyFormatter.format(item.totalAmount)}")
            appendLine("日期：${dateFormatter.format(Date(item.date))}")
            appendLine("发票：$invoiceText")
            if (!item.onlineLink.isNullOrBlank()) appendLine("网购链接：${item.onlineLink}")
            if (!item.notes.isNullOrBlank()) appendLine("备注：${item.notes}")
            appendLine("附件：$attachmentSummary")
        }
    }

    private fun File.toContentUri(context: Context): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            this
        )
    }

    private fun Context.startShareChooser(intent: Intent, title: String) {
        val chooser = Intent.createChooser(intent, title)
        if (this !is Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(chooser)
    }
}
