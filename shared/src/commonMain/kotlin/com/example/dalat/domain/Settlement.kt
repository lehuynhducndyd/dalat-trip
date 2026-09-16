package com.example.dalat.domain

data class SettlementTransaction(
    val fromMemberId: String,
    val toMemberId: String,
    val amount: Long,
)

object SettlementCalculator {
    // The member-id tiebreak keeps the output stable: settlement_marks rows are
    // keyed by the (from, to) pair, so a reshuffled order would orphan everyone's
    // "đã trả" ticks.
    fun simplify(balances: List<MemberBalance>): List<SettlementTransaction> {
        val order = compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }
        val debtors = balances.filter { it.net < 0 }
            .map { it.memberId to -it.net }
            .sortedWith(order)
        val creditors = balances.filter { it.net > 0 }
            .map { it.memberId to it.net }
            .sortedWith(order)

        val transactions = mutableListOf<SettlementTransaction>()
        var debtorIndex = 0
        var creditorIndex = 0
        var debtorRemaining = debtors.firstOrNull()?.second ?: 0L
        var creditorRemaining = creditors.firstOrNull()?.second ?: 0L

        while (debtorIndex < debtors.size && creditorIndex < creditors.size) {
            val amount = minOf(debtorRemaining, creditorRemaining)
            if (amount > 0) {
                transactions += SettlementTransaction(
                    fromMemberId = debtors[debtorIndex].first,
                    toMemberId = creditors[creditorIndex].first,
                    amount = amount,
                )
            }
            debtorRemaining -= amount
            creditorRemaining -= amount
            if (debtorRemaining == 0L) {
                debtorIndex++
                debtorRemaining = debtors.getOrNull(debtorIndex)?.second ?: 0L
            }
            if (creditorRemaining == 0L) {
                creditorIndex++
                creditorRemaining = creditors.getOrNull(creditorIndex)?.second ?: 0L
            }
        }
        return transactions
    }
}
