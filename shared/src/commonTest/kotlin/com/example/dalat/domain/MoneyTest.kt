package com.example.dalat.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyTest {
    @Test
    fun formatsMillionsWithDotSeparators() {
        assertEquals("1.234.567 ₫", formatVnd(1_234_567))
    }

    @Test
    fun formatsSmallAmountsWithoutSeparators() {
        assertEquals("500 ₫", formatVnd(500))
    }

    @Test
    fun formatsZero() {
        assertEquals("0 ₫", formatVnd(0))
    }

    @Test
    fun formatsExactThousand() {
        assertEquals("1.000 ₫", formatVnd(1_000))
    }

    @Test
    fun formatsNegativeAmounts() {
        assertEquals("-5.000 ₫", formatVnd(-5_000))
    }
}
