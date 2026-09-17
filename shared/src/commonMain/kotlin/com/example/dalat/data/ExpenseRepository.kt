package com.example.dalat.data

import com.example.dalat.model.Expense
import com.example.dalat.model.ExpenseShare
import com.example.dalat.model.NewExpense
import com.example.dalat.model.NewExpenseShare
import com.example.dalat.model.SettlementMark
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.flow.Flow

object ExpenseRepository {
    private val realtimeTables = listOf("expenses", "expense_shares", "settlement_marks")

    suspend fun listExpenses(tripId: String): List<Expense> =
        supabase.from("expenses").select {
            filter { eq("trip_id", tripId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<Expense>()

    suspend fun listShares(tripId: String): List<ExpenseShare> =
        supabase.from("expense_shares").select { filter { eq("trip_id", tripId) } }
            .decodeList<ExpenseShare>()

    suspend fun listSettlementMarks(tripId: String): List<SettlementMark> =
        supabase.from("settlement_marks").select { filter { eq("trip_id", tripId) } }
            .decodeList<SettlementMark>()

    // The expense row must exist before its shares, because expense_shares'
    // INSERT policy checks that the caller owns the parent expense.
    suspend fun addExpense(expense: NewExpense, shares: Map<String, Long>) {
        require(shares.isNotEmpty()) { "Chi tiêu phải có ít nhất một người" }
        require(shares.values.sum() == expense.amount) {
            "Tổng phần chia (${shares.values.sum()}) phải bằng số tiền (${expense.amount})"
        }
        val inserted = supabase.from("expenses").insert(expense) { select() }.decodeSingle<Expense>()
        val rows = shares.map { (memberId, amount) ->
            NewExpenseShare(
                expenseId = inserted.id,
                tripId = inserted.tripId,
                memberId = memberId,
                amount = amount,
            )
        }
        supabase.from("expense_shares").insert(rows)
    }

    // Shares are removed by the ON DELETE CASCADE on expense_shares.expense_id.
    suspend fun deleteExpense(expenseId: String) {
        supabase.from("expenses").delete { filter { eq("id", expenseId) } }
    }

    suspend fun setSettlementMark(
        tripId: String,
        fromMemberId: String,
        toMemberId: String,
        isPaid: Boolean,
    ) {
        supabase.from("settlement_marks").upsert(
            SettlementMark(tripId, fromMemberId, toMemberId, isPaid),
        ) {
            onConflict = "trip_id,from_member_id,to_member_id"
        }
    }

    fun realtimeChannel(tripId: String): RealtimeChannel = supabase.channel("trip-$tripId")

    // Must be called BEFORE channel.subscribe() — supabase-kt registers the
    // postgres_changes bindings when the flows are created, and a subscribed
    // channel will not pick up new bindings.
    fun changeFlows(channel: RealtimeChannel, tripId: String): List<Flow<PostgresAction>> =
        realtimeTables.map { tableName ->
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = tableName
                filter("trip_id", FilterOperator.EQ, tripId)
            }
        }
}
