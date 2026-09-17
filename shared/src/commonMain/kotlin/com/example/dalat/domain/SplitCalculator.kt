package com.example.dalat.domain

object SplitCalculator {
    fun splitEqually(total: Long, memberIds: List<String>): Map<String, Long> {
        require(memberIds.isNotEmpty()) { "Cần ít nhất một người tham gia" }
        val base = total / memberIds.size
        val remainder = (total % memberIds.size).toInt()
        return memberIds.mapIndexed { index, id ->
            id to if (index < remainder) base + 1 else base
        }.toMap()
    }
}
