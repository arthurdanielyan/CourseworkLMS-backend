package com.coursework.lms.identity.session

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

/** Email-and-password login. */
data class LoginRequest(
    @NotBlank
    @field:Schema(example = "anna.petrosyan@polytechnic.am")
    val email: String,
    @NotBlank
    @field:Schema(example = "Library#2026")
    val password: String,
)

/** Exchanges a refresh token for a new token pair. */
data class RefreshRequest(
    @NotBlank
    @field:Schema(example = "q3Jx9vZt0bWm2LkR8sYfH4nE6uA1cD7gP5oT3iV0xQw")
    val refreshToken: String,
)

/** A logged-in session as the client stores it. */
data class TokenPairResponse(
    @field:Schema(example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiIsInJvbGUiOiJzdHVkZW50In0.dGhpcy1pcy1hbi1leGFtcGxl")
    val accessToken: String,
    @field:Schema(example = "q3Jx9vZt0bWm2LkR8sYfH4nE6uA1cD7gP5oT3iV0xQw")
    val refreshToken: String,
)
