package com.smartreimburse.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrParserTest {
    private val parser = OcrParser()

    @Test
    fun parsesCoreReceiptFields() {
        val result = parser.parseExpense(
            """
            品名：打印纸
            型号：A4 80g
            数量：2
            单价：12.50
            合计：25.00
            """.trimIndent()
        )
        assertEquals("打印纸", result.name)
        assertEquals("A4 80g", result.model)
        assertEquals(2, result.quantity)
        assertEquals(25.0, result.totalAmount ?: 0.0, 0.0)
    }

    @Test
    fun invoiceNumberRequiresEightToTwelveAlphaNumericCharacters() {
        assertEquals("12345678AB", parser.extractInvoiceNumber("发票号码：12345678AB"))
        assertNull(parser.extractInvoiceNumber("发票号码：123"))
    }
}
