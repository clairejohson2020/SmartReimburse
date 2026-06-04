package com.smartreimburse.ocr

data class OcrExpenseResult(
    val name: String? = null,
    val model: String? = null,
    val quantity: Int? = null,
    val unitPrice: Double? = null,
    val totalAmount: Double? = null
)
