package com.example.dalat.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SplitCalculatorTest {
    @Test
    fun splitsEvenlyWhenDivisible() {
        val result = SplitCalculator.splitEqually(900_000, listOf("a", "b", "c"))
        assertEquals(mapOf("a" to 300_000L, "b" to 300_000L, "c" to 300_000L), result)
    }

    @Test
    fun distributesRemainderToEarliestMembers() {
        val result = SplitCalculator.splitEqually(100_000, listOf("a", "b", "c"))
        assertEquals(mapOf("a" to 33_334L, "b" to 33_333L, "c" to 33_333L), result)
    }

    @Test
    fun sharesAlwaysSumToTotal() {
        for (total in listOf(1L, 7L, 999L, 100_000L, 1_234_567L)) {
            val result = SplitCalculator.splitEqually(total, listOf("a", "b", "c", "d", "e"))
            assertEquals(total, result.values.sum(), "total=$total must reconcile")
        }
    }

    @Test
    fun singleMemberTakesEverything() {
        assertEquals(mapOf("a" to 50_000L), SplitCalculator.splitEqually(50_000, listOf("a")))
    }

    @Test
    fun rejectsEmptyParticipantList() {
        assertFailsWith<IllegalArgumentException> {
            SplitCalculator.splitEqually(1_000, emptyList())
        }
    }
}
