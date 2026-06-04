package com.smartreimburse.export

import android.content.Context
import android.os.Environment
import com.smartreimburse.data.ExpenseWithAttachments
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.apache.poi.xssf.usermodel.XSSFWorkbook

class ExcelExporter(private val context: Context) {
    private val fileDateFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
    private val displayDateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun export(projectName: String, expenses: List<ExpenseWithAttachments>): File {
        val baseDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(context.filesDir, "documents")
        val directory = File(baseDirectory, "exports").apply { mkdirs() }
        val safeProjectName = projectName.toSafeFileName()
        val outputFile = File(
            directory,
            "SmartReimburse_${safeProjectName}_${fileDateFormatter.format(Date())}.xlsx"
        )

        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("报销明细")
            val headerStyle = workbook.createCellStyle().apply {
                val font = workbook.createFont()
                font.bold = true
                setFont(font)
            }

            val headers = listOf(
                "项目名称",
                "名称",
                "型号",
                "数量",
                "金额",
                "日期",
                "发票号码/有无发票",
                "网购链接",
                "备注",
                "附件文件名"
            )
            val headerRow = sheet.createRow(0)
            headers.forEachIndexed { index, title ->
                headerRow.createCell(index).apply {
                    setCellValue(title)
                    cellStyle = headerStyle
                }
            }

            expenses.forEachIndexed { rowIndex, detail ->
                val expense = detail.expense
                val row = sheet.createRow(rowIndex + 1)
                val invoiceLabel = if (expense.hasInvoice) {
                    expense.invoiceNumber?.takeIf { it.isNotBlank() } ?: "有发票"
                } else {
                    "无发票"
                }
                val attachmentNames = detail.attachments.joinToString("; ") {
                    File(it.filePath).name
                }

                row.createCell(0).setCellValue(projectName)
                row.createCell(1).setCellValue(expense.name)
                row.createCell(2).setCellValue(expense.model)
                row.createCell(3).setCellValue(expense.quantity.toDouble())
                row.createCell(4).setCellValue(expense.totalAmount)
                row.createCell(5).setCellValue(displayDateFormatter.format(Date(expense.date)))
                row.createCell(6).setCellValue(invoiceLabel)
                row.createCell(7).setCellValue(expense.onlineLink.orEmpty())
                row.createCell(8).setCellValue(expense.notes.orEmpty())
                row.createCell(9).setCellValue(attachmentNames)
            }

            val columnWidths = listOf(18, 18, 18, 10, 14, 20, 22, 32, 28, 42)
            columnWidths.forEachIndexed { index, width ->
                sheet.setColumnWidth(index, width * 256)
            }

            FileOutputStream(outputFile).use { output ->
                // Apache POI 在这里写出标准 OOXML .xlsx，随后由 FileProvider 暴露只读分享 Uri。
                workbook.write(output)
            }
        }

        return outputFile
    }

    private fun String.toSafeFileName(): String {
        return trim()
            .ifBlank { "项目" }
            .map { char ->
                if (char in setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')) '_' else char
            }
            .joinToString("")
            .take(40)
    }
}
