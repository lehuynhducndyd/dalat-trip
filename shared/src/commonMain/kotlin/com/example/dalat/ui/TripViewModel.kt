package com.example.dalat.ui

import com.example.dalat.data.AuthRepository
import com.example.dalat.data.ExpenseRepository
import com.example.dalat.data.TripRepository
import com.example.dalat.domain.BalanceCalculator
import com.example.dalat.domain.MemberBalance
import com.example.dalat.domain.SettlementCalculator
import com.example.dalat.domain.SettlementTransaction
import com.example.dalat.domain.TripStats
import com.example.dalat.model.Expense
import com.example.dalat.model.ExpenseShare
import com.example.dalat.model.NewExpense
import com.example.dalat.model.SettlementMark
import com.example.dalat.model.Trip
import com.example.dalat.model.TripMember
import io.github.jan.supabase.realtime.RealtimeChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TripUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val trip: Trip? = null,
    val members: List<TripMember> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val shares: List<ExpenseShare> = emptyList(),
    val marks: List<SettlementMark> = emptyList(),
    val currentMemberId: String? = null,
) {
    // Recomputed on read. At five members and a few hundred expenses this is
    // free, and it removes any chance of the tabs showing stale derived data.
    val balances: List<MemberBalance>
        get() = BalanceCalculator.compute(members.map { it.id }, expenses, shares)

    val settlements: List<SettlementTransaction>
        get() = SettlementCalculator.simplify(balances)

    val stats: TripStats get() = TripStats.compute(expenses, shares)

    fun memberName(memberId: String): String =
        members.firstOrNull { it.id == memberId }?.displayName ?: "?"

    fun sharesOf(expenseId: String): List<ExpenseShare> = shares.filter { it.expenseId == expenseId }

    fun isPaid(from: String, to: String): Boolean =
        marks.any { it.fromMemberId == from && it.toMemberId == to && it.isPaid }
}

// A plain class rather than an androidx ViewModel: `viewModel()` needs a
// LocalViewModelStoreOwner to be present, which is an implicit runtime contract
// on Compose for Web. The screen owns this through remember/DisposableEffect
// instead, which fails at compile time if it is wired up wrong.
class TripViewModel(private val tripId: String) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(TripUiState())
    val state: StateFlow<TripUiState> = _state.asStateFlow()

    private var channel: RealtimeChannel? = null

    init {
        refresh()
        startRealtime()
    }

    fun refresh() {
        scope.launch {
            runCatching {
                val trip = TripRepository.getTrip(tripId)
                val members = TripRepository.listMembers(tripId)
                val userId = AuthRepository.currentUserId()
                TripUiState(
                    loading = false,
                    trip = trip,
                    members = members,
                    expenses = ExpenseRepository.listExpenses(tripId),
                    shares = ExpenseRepository.listShares(tripId),
                    marks = ExpenseRepository.listSettlementMarks(tripId),
                    currentMemberId = members.firstOrNull { it.userId == userId }?.id,
                )
            }.onSuccess { _state.value = it }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message) }
        }
    }

    // Any change to the trip just triggers a full reload. Merging individual
    // Realtime payloads would be faster and much easier to get subtly wrong.
    private fun startRealtime() {
        scope.launch {
            val newChannel = ExpenseRepository.realtimeChannel(tripId)
            ExpenseRepository.changeFlows(newChannel, tripId).forEach { flow ->
                launch { flow.collect { refresh() } }
            }
            newChannel.subscribe()
            channel = newChannel
        }
    }

    fun addExpense(expense: NewExpense, shares: Map<String, Long>, onDone: (String?) -> Unit) {
        scope.launch {
            runCatching { ExpenseRepository.addExpense(expense, shares) }
                .onSuccess { refresh(); onDone(null) }
                .onFailure { onDone(it.message ?: "Lưu thất bại") }
        }
    }

    fun deleteExpense(expenseId: String) {
        scope.launch {
            runCatching { ExpenseRepository.deleteExpense(expenseId) }.onSuccess { refresh() }
        }
    }

    fun setSettlementMark(fromMemberId: String, toMemberId: String, isPaid: Boolean) {
        scope.launch {
            runCatching { ExpenseRepository.setSettlementMark(tripId, fromMemberId, toMemberId, isPaid) }
                .onSuccess { refresh() }
        }
    }

    fun dispose() {
        val openChannel = channel
        channel = null
        scope.cancel()
        // The unsubscribe needs a scope that outlives the one just cancelled.
        if (openChannel != null) {
            MainScope().launch { openChannel.unsubscribe() }
        }
    }
}
