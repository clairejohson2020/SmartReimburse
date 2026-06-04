package com.smartreimburse.ocr

class OcrParser {
    private val moneyPatterns = listOf(
        Regex("""¥\s*([0-9]+(?:\.[0-9]{1,2})?)"""),
        Regex("""(?:合计|总计|金额|实付|付款)[^\d¥]{0,12}([0-9]+(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    )
    private val unitPricePattern = Regex("""(?:单价|价格|Unit\s*Price)[：:\s]*¥?\s*([0-9]+(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val quantityPattern = Regex("""(?:数量|Qty|QTY|件数)[：:\s]*([0-9]+)""")
    private val namePattern = Regex("""(?:品名|名称|商品名称|项目名称)[：:\s]*(.+)""")
    private val modelPattern = Regex("""(?:型号|规格型号|规格|Model)[：:\s]*(.+)""", RegexOption.IGNORE_CASE)

    // 发票号解析遵循需求中的规则：匹配“发票号码/发票代码/No/№”后跟 8~12 位数字或字母。
    private val invoicePattern = Regex("""((?:发票号码|发票代码|No|№)[：:\s]*([A-Za-z0-9]{8,12}))""", RegexOption.IGNORE_CASE)

    fun parseExpense(text: String): OcrExpenseResult {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val quantity = quantityPattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val unitPrice = unitPricePattern.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val totalAmount = moneyPatterns
            .flatMap { pattern -> pattern.findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.toDoubleOrNull() } }
            .maxOrNull()
            ?: unitPrice?.times(quantity)

        val model = modelPattern.find(text)?.groupValues?.getOrNull(1)?.cleanField()
        val name = namePattern.find(text)?.groupValues?.getOrNull(1)?.cleanField()
            ?: lines.firstOrNull { it.looksLikeProductName() }?.cleanField()

        return OcrExpenseResult(
            name = name,
            model = model,
            quantity = quantity,
            unitPrice = unitPrice ?: totalAmount?.takeIf { quantity > 0 }?.div(quantity),
            totalAmount = totalAmount
        )
    }

    fun extractInvoiceNumber(text: String): String? {
        return invoicePattern.find(text)?.groupValues?.getOrNull(2)
    }

    private fun String.cleanField(): String {
        return trim()
            .trim('：', ':', ',', '，', ';', '；')
            .take(80)
    }

    private fun String.looksLikeProductName(): Boolean {
        val lower = lowercase()
        if (length < 2) return false
        if (any { it.isLetter() || it.code in 0x4E00..0x9FFF }.not()) return false
        val ignoredWords = listOf("发票", "收据", "合计", "总计", "金额", "税额", "no", "date", "电话", "地址")
        return ignoredWords.none { lower.contains(it) }
    }
}
