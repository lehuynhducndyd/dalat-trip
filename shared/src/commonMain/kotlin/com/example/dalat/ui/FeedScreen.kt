package com.example.dalat.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.dalat.data.AuthRepository
import com.example.dalat.model.Category
import com.example.dalat.model.ExpenseType
import com.example.dalat.ui.components.MoneyText

private fun typeLabel(type: ExpenseType): String = when (type) {
    ExpenseType.GROUP -> "Chia đều"
    ExpenseType.PERSONAL_SELF -> "Cá nhân tự trả"
    ExpenseType.PERSONAL_ITEMIZED -> "Trả hộ theo món"
}

@Composable
fun FeedScreen(state: TripUiState, onDelete: (String) -> Unit) {
    val currentUserId = AuthRepository.currentUserId()
    var dayFilter by remember { mutableStateOf<Int?>(null) }

    if (state.expenses.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Chưa có chi tiêu nào. Bấm + để thêm khoản đầu tiên.")
        }
        return
    }

    val visible = state.expenses.filter { dayFilter == null || it.tripDay == dayFilter }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            FilterChip(
                selected = dayFilter == null,
                onClick = { dayFilter = null },
                label = { Text("Tất cả") },
                modifier = Modifier.padding(end = 6.dp),
            )
            (1..(state.trip?.dayCount ?: 5)).forEach { day ->
                FilterChip(
                    selected = dayFilter == day,
                    onClick = { dayFilter = day },
                    label = { Text("Ngày $day") },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(visible, key = { it.id }) { expense ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                Category.fromId(expense.category).label,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            MoneyText(expense.amount, bold = true)
                        }
                        Text(
                            "${typeLabel(expense.type)} · Ngày ${expense.tripDay} · " +
                                "${state.memberName(expense.payerMemberId)} trả",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        expense.note?.takeIf { it.isNotBlank() }?.let { Text(it) }
                        state.sharesOf(expense.id)
                            .filter { it.amount > 0 }
                            .forEach { share ->
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                    Text(
                                        state.memberName(share.memberId),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    MoneyText(share.amount)
                                }
                            }
                        if (expense.createdBy == currentUserId) {
                            TextButton(onClick = { onDelete(expense.id) }) { Text("Xoá") }
                        }
                    }
                }
            }
        }
    }
}
