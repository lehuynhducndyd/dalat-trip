package com.example.dalat.domain

import com.example.dalat.model.Category
import com.example.dalat.model.Expense
import com.example.dalat.model.ExpenseShare
import com.example.dalat.model.ExpenseType
import kotlin.test.Test
import kotlin.test.assertEquals

private fun statsExpense(id: String, amount: Long, day: Int, category: String) = Expense(
    id = id,
    tripId = "trip",
    payerMemberId = "a",
    type = ExpenseType.GROUP,
    category = category,
    amount = amount,
    note = null,
    tripDay = day,
    createdBy = "user",
    createdAt = "2026-09-16T00:00:00Z",
)

private fun statsShare(expenseId: String, member: String, amount: Long) = ExpenseShare(
    id = "$expenseId-$member",
    expenseId = expenseId,
    tripId = "trip",
    memberId = member,
    amount = amount,
)

class TripStatsTest {
    private val expenses = listOf(
        statsExpense("e1", 300_000, day = 1, category = "lodging"),
        statsExpense("e2", 200_000, day = 1, category = "group_meal"),
        statsExpense("e3", 100_000, day = 2, category = "lodging"),
    )
    private val shares = listOf(
        statsShare("e1", "a", 150_000), statsShare("e1", "b", 150_000),
        statsShare("e2", "a", 200_000),
        statsShare("e3", "b", 100_000),
    )

    @Test
    fun sumsEverything() {
        assertEquals(600_000, TripStats.compute(expenses, shares).total)
    }

    @Test
    fun groupsByTripDay() {
        assertEquals(mapOf(1 to 500_000L, 2 to 100_000L), TripStats.compute(expenses, shares).byDay)
    }

    @Test
    fun groupsByCategory() {
        val byCategory = TripStats.compute(expenses, shares).byCategory
        assertEquals(400_000, byCategory.getValue(Category.LODGING))
        assertEquals(200_000, byCategory.getValue(Category.GROUP_MEAL))
    }

    @Test
    fun sumsConsumptionPerMember() {
        assertEquals(
            mapOf("a" to 350_000L, "b" to 250_000L),
            TripStats.compute(expenses, shares).spendPerMember,
        )
    }

    @Test
    fun handlesEmptyTrip() {
        val stats = TripStats.compute(emptyList(), emptyList())
        assertEquals(0, stats.total)
        assertEquals(emptyMap(), stats.byDay)
    }
}
