package com.example.dalat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class Tab(val label: String) {
    FEED("Chi tiêu"),
    DASHBOARD("Thống kê"),
    SETTLE("Quyết toán"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripHomeScreen(tripId: String, onLeaveTrip: () -> Unit) {
    val viewModel = remember(tripId) { TripViewModel(tripId) }
    DisposableEffect(tripId) { onDispose { viewModel.dispose() } }

    val state by viewModel.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.FEED) }
    var adding by remember { mutableStateOf(false) }

    if (adding) {
        AddExpenseScreen(
            state = state,
            onDismiss = { adding = false },
            onSubmit = { expense, shares, onResult ->
                viewModel.addExpense(expense, shares) { error ->
                    if (error == null) adding = false
                    onResult(error)
                }
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.trip?.name ?: "Đang tải…") },
                actions = {
                    state.trip?.let {
                        Text(
                            "Mã ${it.tripCode}",
                            Modifier.padding(end = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    TextButton(onClick = onLeaveTrip) { Text("Đổi chuyến") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {},
                        label = { Text(entry.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == Tab.FEED) {
                FloatingActionButton(onClick = { adding = true }) {
                    Text("+", style = MaterialTheme.typography.headlineSmall)
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Text("Lỗi: ${state.error}", Modifier.padding(16.dp))
                tab == Tab.FEED -> FeedScreen(state, onDelete = viewModel::deleteExpense)
                tab == Tab.DASHBOARD -> DashboardScreen(state)
                else -> SettleUpScreen(state, onMarkPaid = viewModel::setSettlementMark)
            }
        }
    }
}
