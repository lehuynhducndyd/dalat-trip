package com.example.dalat.domain

import com.example.dalat.model.Expense
import com.example.dalat.model.ExpenseShare
import com.example.dalat.model.ExpenseType
import kotlin.test.Test
import kotlin.test.assertEquals

private fun expense(
    id: String,
    payer: String,
    type: ExpenseType,
    amount: Long,
) = Expense(
    id = id,
    tripId = "trip",
    payerMemberId = payer,
    type = type,
    category = "other",
    amount = amount,
    note = null,
    tripDay = 1,
    createdBy = "user",
    createdAt = "2026-09-16T00:00:00Z",
)

private fun share(expenseId: String, member: String, amount: Long) = ExpenseShare(
    id = "$expenseId-$member",
    expenseId = expenseId,
    tripId = "trip",
    memberId = member,
    amount = amount,
)

class BalancesTest {
    @Test
    fun groupExpenseMakesParticipantsOwePayer() {
        val balances = BalanceCalculator.compute(
            memberIds = listOf("a", "b", "c"),
            expenses = listOf(expense("e1", "a", ExpenseType.GROUP, 300_000)),
            shares = listOf(
                share("e1", "a", 100_000),
                share("e1", "b", 100_000),
                share("e1", "c", 100_000),
            ),
        ).associateBy { it.memberId }

        assertEquals(200_000, balances.getValue("a").net)
        assertEquals(-100_000, balances.getValue("b").net)
        assertEquals(-100_000, balances.getValue("c").net)
    }

    @Test
    fun itemizedExpenseChargesEachPersonTheirOwnAmount() {
        val balances = BalanceCalculator.compute(
            memberIds = listOf("a", "b"),
            expenses = listOf(expense("e1", "a", ExpenseType.PERSONAL_ITEMIZED, 80_000)),
            shares = listOf(share("e1", "a", 30_000), share("e1", "b", 50_000)),
        ).associateBy { it.memberId }

        assertEquals(50_000, balances.getValue("a").net)
        assertEquals(-50_000, balances.getValue("b").net)
    }

    @Test
    fun selfPaidExpenseCreatesNoDebtButCountsAsPaid() {
        val balances = BalanceCalculator.compute(
            memberIds = listOf("a", "b"),
            expenses = listOf(expense("e1", "a", ExpenseType.PERSONAL_SELF, 25_000)),
            shares = listOf(share("e1", "a", 25_000)),
        ).associateBy { it.memberId }

        assertEquals(0, balances.getValue("a").net)
        assertEquals(25_000, balances.getValue("a").paid)
        assertEquals(0, balances.getValue("b").net)
    }

    @Test
    fun netBalancesAlwaysSumToZero() {
        val balances = BalanceCalculator.compute(
            memberIds = listOf("a", "b", "c"),
            expenses = listOf(
                expense("e1", "a", ExpenseType.GROUP, 100_000),
                expense("e2", "b", ExpenseType.PERSONAL_ITEMIZED, 70_000),
            ),
            shares = listOf(
                share("e1", "a", 33_334), share("e1", "b", 33_333), share("e1", "c", 33_333),
                share("e2", "b", 20_000), share("e2", "c", 50_000),
            ),
        )
        assertEquals(0, balances.sumOf { it.net })
    }

    @Test
    fun memberWithNoActivityHasZeroBalance() {
        val balances = BalanceCalculator.compute(
            memberIds = listOf("a", "z"),
            expenses = listOf(expense("e1", "a", ExpenseType.PERSONAL_SELF, 10_000)),
            shares = listOf(share("e1", "a", 10_000)),
        ).associateBy { it.memberId }

        assertEquals(0, balances.getValue("z").paid)
        assertEquals(0, balances.getValue("z").owed)
    }
}
