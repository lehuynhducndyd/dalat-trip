package com.example.dalat.domain

fun formatVnd(amount: Long): String {
    val sign = if (amount < 0) "-" else ""
    val digits = if (amount < 0) (-amount).toString() else amount.toString()
    val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
    return "$sign$grouped ₫"
}
