package com.example.dalat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.dalat.data.AuthRepository
import kotlinx.coroutines.launch

// Translate the cases worth translating and show the server's own words for
// everything else. Guessing a cause hides the real one — an email-rate-limit
// failure reported as "tên này đã có người dùng" sends you looking in the wrong
// place entirely.
private fun authErrorMessage(cause: Throwable, creatingAccount: Boolean): String {
    val raw = cause.message.orEmpty()
    return when {
        raw.contains("over_email_send_rate_limit") || raw.contains("email rate limit") ->
            "Supabase đang chặn vì gửi quá nhiều email xác nhận. " +
                "Cần tắt \"Confirm email\" trong Auth → Providers → Email."
        raw.contains("user_already_exists") || raw.contains("already registered") ->
            "Tên này đã có người dùng. Chọn tên khác, hoặc đăng nhập."
        raw.contains("invalid_credentials") || raw.contains("Invalid login") ->
            "Sai tên hoặc mật khẩu."
        raw.contains("weak_password") ->
            "Mật khẩu quá yếu, cần ít nhất 6 ký tự."
        raw.isBlank() ->
            if (creatingAccount) "Tạo tài khoản thất bại." else "Đăng nhập thất bại."
        else -> raw
    }
}

@Composable
fun SignInScreen() {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var creatingAccount by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val canSubmit = name.isNotBlank() && password.length >= 6 && !busy

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

        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text("Tên của bạn") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Mật khẩu") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            supportingText = { Text("Ít nhất 6 ký tự") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Button(
            enabled = canSubmit,
            onClick = {
                scope.launch {
                    busy = true
                    error = null
                    val result = runCatching {
                        if (creatingAccount) {
                            AuthRepository.signUp(name, password)
                        } else {
                            AuthRepository.signIn(name, password)
                        }
                    }
                    result.onFailure { error = authErrorMessage(it, creatingAccount) }
                    busy = false
                }
            },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(if (creatingAccount) "Tạo tài khoản" else "Đăng nhập")
        }

        TextButton(onClick = { creatingAccount = !creatingAccount; error = null }) {
            Text(
                if (creatingAccount) {
                    "Đã có tài khoản? Đăng nhập"
                } else {
                    "Lần đầu dùng? Tạo tài khoản"
                },
            )
        }
    }
}
