package com.example.dalat.data

import com.example.dalat.domain.accountEmail
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object AuthRepository {
    val sessionStatus: StateFlow<SessionStatus> get() = supabase.auth.sessionStatus

    // The typed name is folded into a synthetic address; the real display name
    // rides along in user metadata so diacritics survive.
    suspend fun signUp(name: String, password: String) {
        supabase.auth.signUpWith(Email) {
            email = accountEmail(name)
            this.password = password
            data = buildJsonObject { put("display_name", name.trim()) }
        }
    }

    suspend fun signIn(name: String, password: String) {
        supabase.auth.signInWith(Email) {
            email = accountEmail(name)
            this.password = password
        }
    }

    suspend fun signOut() {
        supabase.auth.signOut()
    }

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    fun suggestedDisplayName(): String {
        val user = supabase.auth.currentUserOrNull() ?: return ""
        return user.userMetadata?.get("display_name")?.jsonPrimitive?.contentOrNull
            ?: user.email?.substringBefore('@').orEmpty()
    }
}
