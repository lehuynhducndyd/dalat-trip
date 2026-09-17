package com.example.dalat.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class DateDisplayTest {
    @Test
    fun readsDatePickerMillisAsUtcDate() {
        // 2026-12-20T00:00:00Z
        assertEquals("2026-12-20", isoDateFromEpochMillis(1797724800000))
    }

    @Test
    fun formatsIsoDateForVietnameseReaders() {
        assertEquals("20/12/2026", formatDateVi("2026-12-20"))
    }

    @Test
    fun padsSingleDigitDayAndMonth() {
        assertEquals("05/01/2027", formatDateVi("2027-01-05"))
    }
}
