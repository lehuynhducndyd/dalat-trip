package com.example.dalat.domain

// Supabase Auth has no username/password provider, so a display name is folded
// into a synthetic address and password auth does the real work. Members only
// ever type their name.
private const val ACCOUNT_DOMAIN = "dalat.local"

// Built by grouping rather than two parallel strings, where a single missing
// character would silently shift every mapping after it.
private val DIACRITIC_FOLDING: Map<Char, Char> = buildMap {
    fun fold(plain: Char, accented: String) = accented.forEach { put(it, plain) }
    fold('a', "àáạảãâầấậẩẫăằắặẳẵ")
    fold('e', "èéẹẻẽêềếệểễ")
    fold('i', "ìíịỉĩ")
    fold('o', "òóọỏõôồốộổỗơờớợởỡ")
    fold('u', "ùúụủũưừứựửữ")
    fold('y', "ỳýỵỷỹ")
    fold('d', "đ")
}

fun slugifyName(name: String): String {
    val folded = name.lowercase().map { DIACRITIC_FOLDING[it] ?: it }
    val words = folded
        .map { if (it in 'a'..'z' || it in '0'..'9') it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }

    require(words.isNotEmpty()) { "Tên phải có ít nhất một chữ cái hoặc số" }
    return words.joinToString(".")
}

fun accountEmail(name: String): String = "${slugifyName(name)}@$ACCOUNT_DOMAIN"
