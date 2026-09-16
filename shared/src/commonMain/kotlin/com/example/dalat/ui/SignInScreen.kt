package com.example.dalat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.dalat.data.AuthRepository
import kotlinx.coroutines.launch

@Composable
fun SignInScreen() {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Chia tiền Đà Lạt", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Ghi chi tiêu cả nhóm, cuối chuyến biết ai trả ai bao nhiêu.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = { scope.launch { AuthRepository.signInWithGoogle() } }) {
            Text("Đăng nhập với Google")
        }
    }
}
