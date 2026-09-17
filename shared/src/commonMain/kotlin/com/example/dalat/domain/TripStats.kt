package com.example.dalat.domain

import com.example.dalat.model.Category
import com.example.dalat.model.Expense
import com.example.dalat.model.ExpenseShare

data class TripStats(
    val total: Long,
    val byDay: Map<Int, Long>,
    val byCategory: Map<Category, Long>,
    val spendPerMember: Map<String, Long>,
) {
    companion object {
        // spendPerMember is consumption (the sum of that member's shares), not
        // cash paid — it answers "chuyến này tôi tiêu hết bao nhiêu".
        fun compute(expenses: List<Expense>, shares: List<ExpenseShare>): TripStats = TripStats(
            total = expenses.sumOf { it.amount },
            byDay = expenses.groupBy { it.tripDay }
                .mapValues { (_, list) -> list.sumOf { it.amount } }
                .entries.sortedBy { it.key }
                .associate { it.key to it.value },
            byCategory = expenses.groupBy { Category.fromId(it.category) }
                .mapValues { (_, list) -> list.sumOf { it.amount } },
            spendPerMember = shares.groupBy { it.memberId }
                .mapValues { (_, list) -> list.sumOf { it.amount } },
        )
    }
}
