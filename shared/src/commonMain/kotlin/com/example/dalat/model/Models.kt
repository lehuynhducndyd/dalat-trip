package com.example.dalat.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ExpenseType {
    @SerialName("group") GROUP,
    @SerialName("personal_self") PERSONAL_SELF,
    @SerialName("personal_itemized") PERSONAL_ITEMIZED,
}

enum class Category(val id: String, val label: String) {
    LODGING("lodging", "Chỗ ở"),
    TRANSPORT("transport", "Di chuyển"),
    GROUP_MEAL("group_meal", "Ăn nhóm"),
    BREAKFAST("breakfast", "Ăn sáng"),
    DRINKS("drinks", "Nước / Cà phê"),
    SNACKS("snacks", "Đồ ăn vặt"),
    TICKETS("tickets", "Vé / Tham quan"),
    OTHER("other", "Khác");

    companion object {
        fun fromId(id: String): Category = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

@Serializable
data class Trip(
    val id: String,
    val name: String,
    @SerialName("trip_code") val tripCode: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("day_count") val dayCount: Int,
    @SerialName("created_by") val createdBy: String,
)

@Serializable
data class TripMember(
    val id: String,
    @SerialName("trip_id") val tripId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
data class Expense(
    val id: String,
    @SerialName("trip_id") val tripId: String,
    @SerialName("payer_member_id") val payerMemberId: String,
    val type: ExpenseType,
    val category: String,
    val amount: Long,
    val note: String? = null,
    @SerialName("trip_day") val tripDay: Int,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ExpenseShare(
    val id: String,
    @SerialName("expense_id") val expenseId: String,
    @SerialName("trip_id") val tripId: String,
    @SerialName("member_id") val memberId: String,
    val amount: Long,
)

@Serializable
data class SettlementMark(
    @SerialName("trip_id") val tripId: String,
    @SerialName("from_member_id") val fromMemberId: String,
    @SerialName("to_member_id") val toMemberId: String,
    @SerialName("is_paid") val isPaid: Boolean,
)

// Insert DTOs omit server-generated columns (id, created_at, created_by) so
// Postgres defaults apply.
@Serializable
data class NewExpense(
    @SerialName("trip_id") val tripId: String,
    @SerialName("payer_member_id") val payerMemberId: String,
    val type: ExpenseType,
    val category: String,
    val amount: Long,
    val note: String?,
    @SerialName("trip_day") val tripDay: Int,
)

@Serializable
data class NewExpenseShare(
    @SerialName("expense_id") val expenseId: String,
    @SerialName("trip_id") val tripId: String,
    @SerialName("member_id") val memberId: String,
    val amount: Long,
)
