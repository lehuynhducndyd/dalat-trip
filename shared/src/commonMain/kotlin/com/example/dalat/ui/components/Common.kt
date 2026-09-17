package com.example.dalat.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.dalat.domain.formatVnd

@Composable
fun MoneyText(
    amount: Long,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    bold: Boolean = false,
) {
    Text(
        text = formatVnd(amount),
        modifier = modifier,
        color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        // A seven-digit amount would otherwise drop its "₫" onto a second line
        // and shove the surrounding row out of alignment.
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

private val QUICK_STEPS = listOf(10_000L, 50_000L, 100_000L, 500_000L)

// Digits only: VND has no sub-unit, and a text field that silently accepts
// "12.5" would round-trip into the wrong amount. Quick-add chips exist because
// typing six digits for every bowl of phở gets old fast.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    quickAdd: Boolean = true,
) {
    val current = value.toLongOrNull() ?: 0L
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { input -> onValueChange(input.filter { it.isDigit() }.take(12)) },
            label = { Text(label) },
            supportingText = { Text(formatVnd(current)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (quickAdd) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QUICK_STEPS.forEach { step ->
                    AssistChip(
                        onClick = { onValueChange((current + step).toString()) },
                        label = { Text("+${step / 1_000}k") },
                    )
                }
                if (current > 0) {
                    AssistChip(onClick = { onValueChange("") }, label = { Text("Xoá") })
                }
            }
        }
    }
}
