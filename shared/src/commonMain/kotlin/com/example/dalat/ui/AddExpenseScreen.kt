package com.example.dalat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.dalat.domain.SplitCalculator
import com.example.dalat.domain.currentTripDay
import com.example.dalat.domain.formatVnd
import com.example.dalat.model.Category
import com.example.dalat.model.ExpenseType
import com.example.dalat.model.NewExpense
import com.example.dalat.ui.components.AmountField
import com.example.dalat.ui.components.SectionCard
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddExpenseScreen(
    state: TripUiState,
    onDismiss: () -> Unit,
    onSubmit: (NewExpense, Map<String, Long>, (String?) -> Unit) -> Unit,
) {
    val trip = state.trip ?: return
    var type by remember { mutableStateOf(ExpenseType.GROUP) }
    var category by remember { mutableStateOf(Category.GROUP_MEAL) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    // Default to the day the trip is actually on, so the common case needs no tap.
    var day by remember(trip.id) {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        mutableStateOf(currentTripDay(trip.startDate, trip.dayCount, today))
    }
    var payerId by remember {
        mutableStateOf(state.currentMemberId ?: state.members.firstOrNull()?.id ?: "")
    }
    var participants by remember { mutableStateOf(state.members.map { it.id }.toSet()) }
    var itemAmounts by remember { mutableStateOf(state.members.associate { it.id to "" }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // For an itemized bill the total is derived from the per-person amounts, so
    // the shares can never disagree with the expense amount.
    val itemTotal = itemAmounts.values.sumOf { it.toLongOrNull() ?: 0L }
    val amount = if (type == ExpenseType.PERSONAL_ITEMIZED) itemTotal else (amountText.toLongOrNull() ?: 0L)

    fun buildShares(): Map<String, Long> = when (type) {
        ExpenseType.GROUP -> SplitCalculator.splitEqually(amount, participants.toList().sorted())
        ExpenseType.PERSONAL_SELF -> mapOf(payerId to amount)
        ExpenseType.PERSONAL_ITEMIZED -> itemAmounts
            .mapValues { (_, text) -> text.toLongOrNull() ?: 0L }
            .filterValues { it > 0 }
    }

    val canSubmit = amount > 0 && payerId.isNotBlank() && !busy &&
        (type != ExpenseType.GROUP || participants.isNotEmpty()) &&
        (type != ExpenseType.PERSONAL_ITEMIZED || itemTotal > 0)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Thêm chi tiêu", style = MaterialTheme.typography.headlineSmall)

        SectionCard("Loại chi tiêu") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExpenseType.entries.forEach { entry ->
                    FilterChip(
                        selected = type == entry,
                        onClick = { type = entry },
                        label = {
                            Text(
                                when (entry) {
                                    ExpenseType.GROUP -> "Nhóm — chia đều"
                                    ExpenseType.PERSONAL_SELF -> "Cá nhân — tự trả"
                                    ExpenseType.PERSONAL_ITEMIZED -> "Trả hộ — mỗi người một giá"
                                },
                            )
                        },
                    )
                }
            }
        }

        SectionCard("Người trả") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.members.forEach { member ->
                    FilterChip(
                        selected = payerId == member.id,
                        onClick = { payerId = member.id },
                        label = { Text(member.displayName) },
                    )
                }
            }
        }

        if (type == ExpenseType.PERSONAL_ITEMIZED) {
            SectionCard("Mỗi người bao nhiêu") {
                state.members.forEach { member ->
                    AmountField(
                        value = itemAmounts[member.id].orEmpty(),
                        onValueChange = { itemAmounts = itemAmounts + (member.id to it) },
                        label = member.displayName,
                        // One chip row per member would bury the form.
                        quickAdd = false,
                    )
                }
                Text("Tổng hoá đơn: ${formatVnd(itemTotal)}", Modifier.padding(top = 8.dp))
            }
        } else {
            SectionCard("Số tiền") {
                AmountField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = "Số tiền (VND)",
                )
            }
        }

        if (type == ExpenseType.GROUP) {
            SectionCard("Chia cho ai") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.members.forEach { member ->
                        FilterChip(
                            selected = member.id in participants,
                            onClick = {
                                participants = if (member.id in participants) {
                                    participants - member.id
                                } else {
                                    participants + member.id
                                }
                            },
                            label = { Text(member.displayName) },
                        )
                    }
                }
                if (participants.isNotEmpty() && amount > 0) {
                    Text(
                        "Mỗi người khoảng ${formatVnd(amount / participants.size)}",
                        Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        SectionCard("Hạng mục") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Category.entries.forEach { entry ->
                    FilterChip(
                        selected = category == entry,
                        onClick = { category = entry },
                        label = { Text(entry.label) },
                    )
                }
            }
        }

        SectionCard("Ngày") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..trip.dayCount).forEach { d ->
                    FilterChip(
                        selected = day == d,
                        onClick = { day = d },
                        label = { Text("Ngày $d") },
                    )
                }
            }
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Ghi chú") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
            Button(
                enabled = canSubmit,
                onClick = {
                    busy = true
                    error = null
                    val newExpense = NewExpense(
                        tripId = trip.id,
                        payerMemberId = payerId,
                        type = type,
                        category = category.id,
                        amount = amount,
                        note = note.takeIf { it.isNotBlank() },
                        tripDay = day,
                    )
                    onSubmit(newExpense, buildShares()) { failure ->
                        busy = false
                        error = failure
                    }
                },
            ) { Text("Lưu") }
        }
    }
}
