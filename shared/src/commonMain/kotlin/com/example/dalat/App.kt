package com.example.dalat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.dalat.data.AuthRepository
import com.example.dalat.ui.DalatTheme
import com.example.dalat.ui.SignInScreen
import com.example.dalat.ui.TripHomeScreen
import com.example.dalat.ui.TripPickerScreen
import io.github.jan.supabase.auth.status.SessionStatus

@Composable
fun App() {
    DalatTheme {
        Surface(Modifier.fillMaxSize().safeContentPadding()) {
            val status by AuthRepository.sessionStatus.collectAsState()
            when (status) {
                is SessionStatus.Authenticated -> SignedInApp()
                is SessionStatus.NotAuthenticated -> SignInScreen()
                else -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun SignedInApp() {
    var openTripId by remember { mutableStateOf<String?>(null) }
    val tripId = openTripId
    if (tripId == null) {
        TripPickerScreen(onTripOpened = { openTripId = it })
    } else {
        TripHomeScreen(tripId = tripId, onLeaveTrip = { openTripId = null })
    }
}
