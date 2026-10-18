package com.coursework.lms.core.security

/** Names of the custom claims an access token carries next to `sub`, the user's id. */
object AccessTokenClaims {

    const val ROLE = "role"
    const val PERMISSIONS = "perms"
}
