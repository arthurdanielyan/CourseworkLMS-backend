package com.coursework.lms.core.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Signing and lifetime settings for access tokens, bound from `lms.jwt.*`. */
@ConfigurationProperties("lms.jwt")
data class JwtProperties(
    val secret: String,
    val issuer: String = "lms",
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
)
