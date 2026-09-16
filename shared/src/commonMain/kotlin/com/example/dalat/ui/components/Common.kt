package com.example.dalat.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

// Digits only: VND has no sub-unit, and a text field that silently accepts
// "12.5" would round-trip into the wrong amount.
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() }.take(12)) },
        label = { Text(label) },
        supportingText = { Text(formatVnd(value.toLongOrNull() ?: 0L)) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}
