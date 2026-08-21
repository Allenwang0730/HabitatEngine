package com.ailun.habitat.app.auth

data class LoginRequest(val username: String, val password: String)
data class RegisterRequest(val username: String, val email: String, val password: String)
data class AuthResponse(
    val token: String,
    val tokenType: String,
    val expiresIn: Long,
    val userId: String,
    val username: String,
)
data class ApiErrorResponse(
    val status: Int,
    val error: String,
    val message: String,
    val path: String = "",
)
