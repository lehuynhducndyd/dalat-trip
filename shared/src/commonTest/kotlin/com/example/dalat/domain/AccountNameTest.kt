package com.example.dalat.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountNameTest {
    @Test
    fun foldsVietnameseDiacritics() {
        assertEquals("duc", slugifyName("Đức"))
    }

    @Test
    fun joinsWordsWithDots() {
        assertEquals("le.huynh.duc", slugifyName("Lê Huỳnh Đức"))
    }

    @Test
    fun trimsAndCollapsesWhitespace() {
        assertEquals("nguyen.anh", slugifyName("  Nguyễn   Ánh  "))
    }

    @Test
    fun coversEveryVowelGroup() {
        assertEquals("aeiouy", slugifyName("ăêĩôưỳ"))
        assertEquals("aaeeiioouuyd", slugifyName("àạèẹìịòọùụỳđ"))
    }

    @Test
    fun keepsPlainAsciiUnchanged() {
        assertEquals("nam", slugifyName("Nam"))
    }

    @Test
    fun dropsPunctuation() {
        assertEquals("nam.k9", slugifyName("Nam - K9!"))
    }

    @Test
    fun rejectsNameWithNoUsableCharacters() {
        assertFailsWith<IllegalArgumentException> { slugifyName("   ") }
        assertFailsWith<IllegalArgumentException> { slugifyName("!!!") }
    }

    @Test
    fun buildsAccountEmailFromName() {
        assertEquals("le.huynh.duc@dalat.local", accountEmail("Lê Huỳnh Đức"))
    }

    @Test
    fun differentCasingMapsToTheSameAccount() {
        assertEquals(accountEmail("đức"), accountEmail("ĐỨC"))
    }
}
