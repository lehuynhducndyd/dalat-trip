package com.example.dalat.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun balance(id: String, net: Long) =
    if (net >= 0) MemberBalance(id, paid = net, owed = 0) else MemberBalance(id, paid = 0, owed = -net)

class SettlementTest {
    @Test
    fun settledGroupNeedsNoTransactions() {
        val result = SettlementCalculator.simplify(
            listOf(balance("a", 0), balance("b", 0)),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun twoDebtorsPayTheSingleCreditor() {
        val result = SettlementCalculator.simplify(
            listOf(balance("a", 200_000), balance("b", -100_000), balance("c", -100_000)),
        )
        assertEquals(2, result.size)
        assertTrue(result.all { it.toMemberId == "a" })
        assertEquals(200_000, result.sumOf { it.amount })
    }

    @Test
    fun collapsesCircularDebtIntoOneTransfer() {
        // a owes b 50k, b owes c 50k -> a should just pay c.
        val result = SettlementCalculator.simplify(
            listOf(balance("a", -50_000), balance("b", 0), balance("c", 50_000)),
        )
        assertEquals(
            listOf(SettlementTransaction("a", "c", 50_000)),
            result,
        )
    }

    @Test
    fun neverExceedsMemberCountMinusOneTransactions() {
        val balances = listOf(
            balance("a", 300_000), balance("b", 150_000),
            balance("c", -200_000), balance("d", -150_000), balance("e", -100_000),
        )
        val result = SettlementCalculator.simplify(balances)
        assertTrue(result.size <= balances.size - 1, "got ${result.size} transactions")
    }

    @Test
    fun transactionsFullyCancelEveryBalance() {
        val balances = listOf(
            balance("a", 123_456), balance("b", -23_456),
            balance("c", -100_000), balance("d", 0),
        )
        val result = SettlementCalculator.simplify(balances)
        val settled = balances.associate { it.memberId to it.net }.toMutableMap()
        for (t in result) {
            settled[t.fromMemberId] = settled.getValue(t.fromMemberId) + t.amount
            settled[t.toMemberId] = settled.getValue(t.toMemberId) - t.amount
        }
        assertTrue(settled.values.all { it == 0L }, "leftover balances: $settled")
    }

    @Test
    fun outputIsDeterministic() {
        val balances = listOf(
            balance("b", -100_000), balance("a", -100_000), balance("c", 200_000),
        )
        assertEquals(SettlementCalculator.simplify(balances), SettlementCalculator.simplify(balances))
    }
}
