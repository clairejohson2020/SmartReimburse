package com.smartreimburse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test
    fun parsesAndRoundsToIntegerCents() {
        assertEquals(1234L, Money.parseCents("12.34"))
        assertEquals(11L, Money.parseCents("0.105"))
        assertEquals(12.34, Money.toDouble(1234L), 0.0)
    }

    @Test
    fun rejectsNegativeOrMalformedMoney() {
        assertNull(Money.parseCents("-1"))
        assertNull(Money.parseCents("abc"))
    }
}
