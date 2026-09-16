package com.example.dalat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.dalat.data.AuthRepository
import com.example.dalat.data.TripRepository
import com.example.dalat.model.Trip
import com.example.dalat.ui.components.SectionCard
import kotlinx.coroutines.launch

@Composable
fun TripPickerScreen(onTripOpened: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var trips by remember { mutableStateOf<List<Trip>>(emptyList()) }
    var displayName by remember { mutableStateOf(AuthRepository.suggestedDisplayName()) }
    var tripName by remember { mutableStateOf("Đà Lạt") }
    var startDate by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        error = runCatching { trips = TripRepository.listTrips() }.exceptionOrNull()?.message
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Chuyến đi của bạn", style = MaterialTheme.typography.headlineSmall)

        if (trips.isEmpty()) {
            Text("Chưa có chuyến nào.", Modifier.padding(vertical = 8.dp))
        } else {
            SectionCard("Mở chuyến có sẵn") {
                trips.forEach { trip ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable { onTripOpened(trip.id) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(trip.name, style = MaterialTheme.typography.titleSmall)
                        Text("Mã: ${trip.tripCode} · ${trip.dayCount} ngày từ ${trip.startDate}")
                    }
                    HorizontalDivider()
                }
            }
        }

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Tên hiển thị của bạn") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        SectionCard("Tạo chuyến mới") {
            OutlinedTextField(
                value = tripName,
                onValueChange = { tripName = it },
                label = { Text("Tên chuyến") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = startDate,
                onValueChange = { startDate = it },
                label = { Text("Ngày bắt đầu (YYYY-MM-DD)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                enabled = !busy && displayName.isNotBlank() && startDate.length == 10,
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { TripRepository.createTrip(tripName, startDate, 5, displayName) }
                            .onSuccess { onTripOpened(it) }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Tạo chuyến") }
        }

        SectionCard("Vào chuyến bằng mã") {
            OutlinedTextField(
                value = joinCode,
                onValueChange = { joinCode = it.uppercase().take(6) },
                label = { Text("Mã chuyến (6 ký tự)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !busy && joinCode.length == 6 && displayName.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { TripRepository.joinTrip(joinCode, displayName) }
                            .onSuccess { onTripOpened(it) }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Vào chuyến") }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }

        TextButton(onClick = { scope.launch { AuthRepository.signOut() } }) { Text("Đăng xuất") }
    }
}
