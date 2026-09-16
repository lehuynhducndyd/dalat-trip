package com.example.dalat

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform