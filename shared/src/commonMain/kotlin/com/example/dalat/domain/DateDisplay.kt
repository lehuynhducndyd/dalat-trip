package com.example.dalat.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// DatePicker hands back UTC midnight for the chosen day, so read it back in UTC
// or a negative-offset timezone would shift the date a day earlier.
fun isoDateFromEpochMillis(millis: Long): String =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date.toString()

fun formatDateVi(isoDate: String): String {
    val date = LocalDate.parse(isoDate)
    val day = date.day.toString().padStart(2, '0')
    val month = date.monthNumber.toString().padStart(2, '0')
    return "$day/$month/${date.year}"
}
