package com.example.dalat.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class TripDayTest {
    private val start = "2026-12-20"

    @Test
    fun firstDayOfTheTripIsDayOne() {
        assertEquals(1, currentTripDay(start, 5, LocalDate.parse("2026-12-20")))
    }

    @Test
    fun thirdDayOfTheTripIsDayThree() {
        assertEquals(3, currentTripDay(start, 5, LocalDate.parse("2026-12-22")))
    }

    @Test
    fun lastDayOfTheTripIsDayCount() {
        assertEquals(5, currentTripDay(start, 5, LocalDate.parse("2026-12-24")))
    }

    @Test
    fun beforeTheTripClampsToDayOne() {
        assertEquals(1, currentTripDay(start, 5, LocalDate.parse("2026-11-01")))
    }

    @Test
    fun afterTheTripClampsToTheLastDay() {
        assertEquals(5, currentTripDay(start, 5, LocalDate.parse("2027-03-01")))
    }

    @Test
    fun handlesMonthBoundaries() {
        assertEquals(3, currentTripDay("2026-12-30", 5, LocalDate.parse("2027-01-01")))
    }
}
