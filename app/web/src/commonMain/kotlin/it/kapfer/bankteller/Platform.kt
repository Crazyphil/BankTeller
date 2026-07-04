package it.kapfer.bankteller

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
