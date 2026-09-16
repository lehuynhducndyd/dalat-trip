package com.example.dalat.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.example.dalat.domain.formatVnd
import com.example.dalat.model.Category
import com.example.dalat.model.ExpenseType
import com.example.dalat.model.NewExpense
import com.example.dalat.ui.components.AmountField
import com.example.dalat.ui.components.SectionCard

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
    var day by remember { mutableStateOf(1) }
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
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        SectionCard("Người trả") {
            state.members.forEach { member ->
                FilterChip(
                    selected = payerId == member.id,
                    onClick = { payerId = member.id },
                    label = { Text(member.displayName) },
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        if (type == ExpenseType.PERSONAL_ITEMIZED) {
            SectionCard("Mỗi người bao nhiêu") {
                state.members.forEach { member ->
                    AmountField(
                        value = itemAmounts[member.id].orEmpty(),
                        onValueChange = { itemAmounts = itemAmounts + (member.id to it) },
                        label = member.displayName,
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
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
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
            Category.entries.forEach { entry ->
                FilterChip(
                    selected = category == entry,
                    onClick = { category = entry },
                    label = { Text(entry.label) },
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        SectionCard("Ngày") {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                (1..trip.dayCount).forEach { d ->
                    FilterChip(
                        selected = day == d,
                        onClick = { day = d },
                        label = { Text("Ngày $d") },
                        modifier = Modifier.padding(end = 6.dp),
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
