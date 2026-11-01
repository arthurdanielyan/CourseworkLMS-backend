package com.coursework.lms.identity.registration

import com.coursework.lms.identity.validation.PolytechnicEmail
import com.coursework.lms.identity.validation.StrongPassword
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

/** The signup form. */
// Our own constraints need the explicit field target: by default Kotlin puts them on the property, where the
// validator never looks.
data class StartRegistrationRequest(
    @field:PolytechnicEmail
    @field:Schema(example = "anna.petrosyan@polytechnic.am")
    val email: String,
    @NotBlank
    @Size(min = 2, max = 100)
    @field:Schema(example = "Anna Petrosyan")
    val fullName: String,
    @field:StrongPassword
    @field:Schema(example = "Library#2026")
    val password: String,
)

/** Asks for a new Verification Code for a pending signup. */
data class ResendCodeRequest(
    @field:PolytechnicEmail
    @field:Schema(example = "anna.petrosyan@polytechnic.am")
    val email: String,
)

/** The Verification Code a user typed in. */
data class VerifyCodeRequest(
    @field:PolytechnicEmail
    @field:Schema(example = "anna.petrosyan@polytechnic.am")
    val email: String,
    @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits")
    @field:Schema(example = "482913")
    val code: String,
)

/** A signup waiting for its code. */
data class PendingRegistrationResponse(
    @field:Schema(example = "anna.petrosyan@polytechnic.am")
    val email: String,
    @field:Schema(example = "2026-11-01T18:10:00Z")
    val codeExpiresAt: Instant,
    @field:Schema(example = "2026-11-01T18:02:00Z")
    val resendAvailableAt: Instant,
)
