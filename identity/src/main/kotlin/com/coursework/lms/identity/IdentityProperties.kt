package com.coursework.lms.identity

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Limits of signup verification and of login sessions, bound from `lms.identity.*`. [pendingRegistrationRetention] is
 * how long a Pending Registration is kept after its code expires, so its user can still ask for a new one.
 */
@ConfigurationProperties("lms.identity")
data class IdentityProperties(
    val codeTtl: Duration = Duration.ofMinutes(10),
    val maxCodeAttempts: Int = 5,
    val resendCooldown: Duration = Duration.ofMinutes(2),
    val refreshTokenTtl: Duration = Duration.ofDays(30),
    val pendingRegistrationRetention: Duration = Duration.ofDays(1),
)
