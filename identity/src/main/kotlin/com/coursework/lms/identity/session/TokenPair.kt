package com.coursework.lms.identity.session

/** What a logged-in client holds: a short-lived access token and the refresh token that renews it. */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
)
