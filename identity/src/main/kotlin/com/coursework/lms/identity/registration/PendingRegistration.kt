package com.coursework.lms.identity.registration

import java.time.Instant

/** A signup waiting for its Verification Code: until when the code works, and when another may be sent. */
data class PendingRegistration(
    val email: String,
    val codeExpiresAt: Instant,
    val resendAvailableAt: Instant,
)
