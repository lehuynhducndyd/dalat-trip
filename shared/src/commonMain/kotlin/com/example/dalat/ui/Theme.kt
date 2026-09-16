package com.example.dalat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DalatColors = lightColorScheme(
    primary = Color(0xFF2E7D5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8E6CE),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF7A5C2E),
    surface = Color(0xFFFCFDF8),
    background = Color(0xFFF4F7F2),
    error = Color(0xFFBA1A1A),
)

@Composable
fun DalatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DalatColors, content = content)
}
