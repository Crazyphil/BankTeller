package it.kapfer.bankteller.server

import kotlinx.serialization.Serializable

@Serializable
data class UserSession(val username: String)

@Serializable
data class LoginRequest(val username: String, val password: String)
