package com.example.dalat.data

import com.example.dalat.model.Trip
import com.example.dalat.model.TripMember
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object TripRepository {
    suspend fun listTrips(): List<Trip> =
        supabase.from("trips").select().decodeList<Trip>()

    suspend fun getTrip(tripId: String): Trip =
        supabase.from("trips").select { filter { eq("id", tripId) } }.decodeSingle<Trip>()

    suspend fun listMembers(tripId: String): List<TripMember> =
        supabase.from("trip_members").select { filter { eq("trip_id", tripId) } }
            .decodeList<TripMember>()

    // Both writes go through SECURITY DEFINER RPCs — see the spec's RLS section
    // for why direct inserts are not possible here.
    suspend fun createTrip(
        name: String,
        startDate: String,
        dayCount: Int,
        displayName: String,
    ): String = supabase.postgrest.rpc(
        "create_trip",
        buildJsonObject {
            put("p_name", name)
            put("p_start_date", startDate)
            put("p_day_count", dayCount)
            put("p_display_name", displayName)
        },
    ).decodeAs<String>()

    suspend fun joinTrip(tripCode: String, displayName: String): String = supabase.postgrest.rpc(
        "join_trip",
        buildJsonObject {
            put("p_trip_code", tripCode)
            put("p_display_name", displayName)
        },
    ).decodeAs<String>()
}
