package com.smartreimburse.ui.components

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val currencyFormatter = NumberFormat.getCurrencyInstance(Locale.CHINA)
private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

fun formatCurrency(value: Double): String = currencyFormatter.format(value)

fun formatDate(millis: Long): String = dateFormatter.format(Date(millis))
