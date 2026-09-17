package com.example.dalat.domain

import kotlinx.datetime.LocalDate

// Which day of the trip a given date falls on, clamped into 1..dayCount so a
// phone opened before departure or after getting home still lands on a real day.
fun currentTripDay(startDate: String, dayCount: Int, today: LocalDate): Int {
    val start = LocalDate.parse(startDate)
    val offset = today.toEpochDays() - start.toEpochDays()
    return (offset + 1).coerceIn(1L, dayCount.toLong()).toInt()
}
