package com.example.dalat.domain

import com.example.dalat.model.Expense
import com.example.dalat.model.ExpenseShare

data class MemberBalance(
    val memberId: String,
    val paid: Long,
    val owed: Long,
) {
    val net: Long get() = paid - owed
}

object BalanceCalculator {
    // personal_self expenses need no special case: their single share belongs to
    // the payer, so paid and owed cancel out and `net` is unaffected, while
    // `paid` still reflects real cash out of pocket.
    fun compute(
        memberIds: List<String>,
        expenses: List<Expense>,
        shares: List<ExpenseShare>,
    ): List<MemberBalance> {
        val paid = mutableMapOf<String, Long>()
        val owed = mutableMapOf<String, Long>()

        for (expense in expenses) {
            paid[expense.payerMemberId] = (paid[expense.payerMemberId] ?: 0L) + expense.amount
        }
        for (share in shares) {
            owed[share.memberId] = (owed[share.memberId] ?: 0L) + share.amount
        }

        return memberIds.map { id ->
            MemberBalance(memberId = id, paid = paid[id] ?: 0L, owed = owed[id] ?: 0L)
        }
    }
}
