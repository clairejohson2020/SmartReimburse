package com.smartreimburse.data

import java.math.BigDecimal
import java.math.RoundingMode

object Money {
    fun parseCents(value: String): Long? = runCatching {
        val decimal = value.trim().toBigDecimal()
        if (decimal.signum() < 0) return null
        decimal.movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()

    fun fromDouble(value: Double): Long = BigDecimal.valueOf(value)
        .movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()

    fun toDouble(cents: Long): Double = cents.toDouble() / 100.0
}
