package com.coursework.lms.identity.registration

import com.coursework.lms.identity.validation.PolytechnicEmail
import com.coursework.lms.identity.validation.StrongPassword
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

/** The signup form. */
// Our own constraints need the explicit field target: by default Kotlin puts them on the property, where the
// validator never looks.
data class StartRegistrationRequest(
    @field:PolytechnicEmail
    val email: String,
    @NotBlank
    @Size(min = 2, max = 100)
    val fullName: String,
    @field:StrongPassword
    val password: String,
)

/** Asks for a new Verification Code for a pending signup. */
data class ResendCodeRequest(
    @field:PolytechnicEmail
    val email: String,
)

/** The Verification Code a user typed in. */
data class VerifyCodeRequest(
    @field:PolytechnicEmail
    val email: String,
    @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits")
    val code: String,
)

/** A signup waiting for its code. */
data class PendingRegistrationResponse(
    val email: String,
    val codeExpiresAt: Instant,
    val resendAvailableAt: Instant,
)
