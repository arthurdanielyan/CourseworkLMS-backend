package com.coursework.lms.identity.session

import jakarta.validation.constraints.NotBlank

/** Email-and-password login. */
data class LoginRequest(
    @NotBlank
    val email: String,
    @NotBlank
    val password: String,
)

/** Exchanges a refresh token for a new token pair. */
data class RefreshRequest(
    @NotBlank
    val refreshToken: String,
)

/** A logged-in session as the client stores it. */
data class TokenPairResponse(
    val accessToken: String,
    val refreshToken: String,
)
