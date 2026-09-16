package com.example.dalat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.dalat.domain.formatVnd
import com.example.dalat.ui.components.MoneyText
import com.example.dalat.ui.components.SectionCard

@Composable
private fun StatRow(label: String, amount: Long, total: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(label)
            MoneyText(amount)
        }
        LinearProgressIndicator(
            progress = { if (total > 0) amount.toFloat() / total.toFloat() else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun DashboardScreen(state: TripUiState) {
    val stats = state.stats
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionCard("Tổng chi cả nhóm") {
            Text(formatVnd(stats.total), style = MaterialTheme.typography.headlineMedium)
            Text("${state.expenses.size} khoản · ${state.members.size} thành viên")
        }

        SectionCard("Theo ngày") {
            if (stats.byDay.isEmpty()) Text("Chưa có dữ liệu")
            stats.byDay.forEach { (day, amount) -> StatRow("Ngày $day", amount, stats.total) }
        }

        SectionCard("Theo hạng mục") {
            if (stats.byCategory.isEmpty()) Text("Chưa có dữ liệu")
            stats.byCategory.entries
                .sortedByDescending { it.value }
                .forEach { (category, amount) -> StatRow(category.label, amount, stats.total) }
        }

        SectionCard("Mỗi người tiêu bao nhiêu") {
            Text(
                "Tính theo phần mình dùng, không phải tiền đã ứng ra.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.members.forEach { member ->
                StatRow(member.displayName, stats.spendPerMember[member.id] ?: 0L, stats.total)
            }
        }
    }
}
