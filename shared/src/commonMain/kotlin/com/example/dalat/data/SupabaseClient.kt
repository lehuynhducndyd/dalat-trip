package com.example.dalat.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json

// The publishable (anon) key is designed to ship in browser code; RLS is what
// protects the data. The service role key must never appear in this repo.
// This is the legacy JWT-style anon key rather than the newer sb_publishable_
// one, because supabase-kt also feeds this value to the Realtime socket as a
// JWT when no user session exists.
private const val SUPABASE_URL = "https://gkqtbmidixjhrlxcgagh.supabase.co"
private const val SUPABASE_ANON_KEY =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
        "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImdrcXRibWlkaXhqaHJseGNnYWdoIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODk1NTY2MjEsImV4cCI6MjEwNTEzMjYyMX0." +
        "NpcoZeHO15zZ0Q1wCDok6-CCyDJNwMPGB8G6aVGDl34"

val supabase: SupabaseClient by lazy {
    createSupabaseClient(
        supabaseUrl = SUPABASE_URL,
        supabaseKey = SUPABASE_ANON_KEY,
    ) {
        // Tables return columns the Kotlin models deliberately omit (created_at,
        // joined_at, ...). Without ignoreUnknownKeys every SELECT would throw.
        defaultSerializer = KotlinXSerializer(Json { ignoreUnknownKeys = true })

        install(Auth) {
            flowType = FlowType.PKCE
        }
        install(Postgrest)
        install(Realtime)
    }
}
