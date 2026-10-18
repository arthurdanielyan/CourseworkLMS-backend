package com.coursework.lms.core.security

/**
 * Request path patterns reachable without an access token. Every module registers its own as a bean;
 * all other paths require authentication.
 */
data class PublicEndpoints(
    val patterns: List<String>,
)
