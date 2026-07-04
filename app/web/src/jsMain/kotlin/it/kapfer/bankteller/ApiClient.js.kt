package it.kapfer.bankteller

import io.ktor.client.*
import io.ktor.client.engine.js.*

actual fun createHttpClient(): HttpClient = HttpClient(Js)
