package com.example.dalat.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object AuthRepository {
    val sessionStatus: StateFlow<SessionStatus> get() = supabase.auth.sessionStatus

    suspend fun signInWithGoogle() {
        supabase.auth.signInWith(Google)
    }

    suspend fun signOut() {
        supabase.auth.signOut()
    }

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    fun suggestedDisplayName(): String {
        val user = supabase.auth.currentUserOrNull() ?: return ""
        val metadata = user.userMetadata
        val fromMetadata = metadata?.get("full_name")?.jsonPrimitive?.contentOrNull
            ?: metadata?.get("name")?.jsonPrimitive?.contentOrNull
        return fromMetadata ?: user.email?.substringBefore('@') ?: ""
    }
}
