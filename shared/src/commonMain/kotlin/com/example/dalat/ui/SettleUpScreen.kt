package com.example.dalat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import com.example.dalat.domain.formatVnd
import com.example.dalat.ui.components.MoneyText
import com.example.dalat.ui.components.SectionCard

@Composable
fun SettleUpScreen(state: TripUiState, onMarkPaid: (String, String, Boolean) -> Unit) {
    var showDetail by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionCard("Số dư từng người") {
            state.balances.sortedByDescending { it.net }.forEach { balance ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    Arrangement.SpaceBetween,
                    Alignment.CenterVertically,
                ) {
                    Column {
                        Text(state.memberName(balance.memberId))
                        Text(
                            "Đã ứng ${formatVnd(balance.paid)} · Đã dùng ${formatVnd(balance.owed)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        MoneyText(
                            balance.net,
                            color = if (balance.net >= 0) {
                                BalancePositive
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            bold = true,
                        )
                        Text(
                            when {
                                balance.net > 0 -> "được nhận"
                                balance.net < 0 -> "cần trả"
                                else -> "xong"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider()
            }
        }

        SectionCard("Cần chuyển khoản") {
            if (state.settlements.isEmpty()) {
                Text("Cả nhóm đã cân bằng, không ai phải trả ai.")
            } else {
                Text(
                    "${state.settlements.size} giao dịch là đủ để cân bằng cả nhóm.",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.settlements.forEach { transaction ->
                    val paid = state.isPaid(transaction.fromMemberId, transaction.toMemberId)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        Arrangement.SpaceBetween,
                        Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${state.memberName(transaction.fromMemberId)} → " +
                                    state.memberName(transaction.toMemberId),
                            )
                            MoneyText(transaction.amount, bold = true)
                        }
                        Checkbox(
                            checked = paid,
                            onCheckedChange = {
                                onMarkPaid(transaction.fromMemberId, transaction.toMemberId, it)
                            },
                        )
                    }
                }
            }
        }

        TextButton(onClick = { showDetail = !showDetail }) {
            Text(if (showDetail) "Ẩn chi tiết" else "Xem chi tiết cách tính")
        }

        if (showDetail) {
            SectionCard("Chi tiết từng khoản") {
                state.expenses.forEach { expense ->
                    Text(
                        "${formatVnd(expense.amount)} · ${state.memberName(expense.payerMemberId)} trả" +
                            (expense.note?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    state.sharesOf(expense.id).filter { it.amount > 0 }.forEach { share ->
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), Arrangement.SpaceBetween) {
                            Text(
                                state.memberName(share.memberId),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            MoneyText(share.amount)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }
}
