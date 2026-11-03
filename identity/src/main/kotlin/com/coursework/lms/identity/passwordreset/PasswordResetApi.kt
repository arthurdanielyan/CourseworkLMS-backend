package com.coursework.lms.identity.passwordreset

import com.coursework.lms.identity.validation.PolytechnicEmail
import com.coursework.lms.identity.validation.StrongPassword
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import java.time.Instant

/** When the code sent for a reset stops working and when another may be asked for. */
data class PasswordResetSent(
    val email: String,
    val codeExpiresAt: Instant,
    val resendAvailableAt: Instant,
)

data class StartPasswordResetRequest(
    @field:PolytechnicEmail
    @field:Schema(example = "anna.petrosyan@polytechnic.am")
    val email: String,
)

data class VerifyPasswordResetRequest(
    @field:PolytechnicEmail
    @field:Schema(example = "anna.petrosyan@polytechnic.am")
    val email: String,
    @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits")
    @field:Schema(example = "482913")
    val code: String,
)

data class CompletePasswordResetRequest(
    @field:PolytechnicEmail
    @field:Schema(example = "anna.petrosyan@polytechnic.am")
    val email: String,
    @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits")
    @field:Schema(example = "482913")
    val code: String,
    @field:StrongPassword
    @field:Schema(example = "Library#2027")
    val newPassword: String,
)

data class PasswordResetSentResponse(
    @field:Schema(example = "anna.petrosyan@polytechnic.am")
    val email: String,
    @field:Schema(example = "2026-11-16T18:10:00Z")
    val codeExpiresAt: Instant,
    @field:Schema(example = "2026-11-16T18:02:00Z")
    val resendAvailableAt: Instant,
)
