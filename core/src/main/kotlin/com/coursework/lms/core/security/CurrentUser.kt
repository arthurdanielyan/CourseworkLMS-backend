package com.coursework.lms.core.security

/** The authenticated user a request acts for, as carried by their access token. */
data class CurrentUser(
    val id: Int,
    val role: String,
    val permissions: Set<String>,
)
