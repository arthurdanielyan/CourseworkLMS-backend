package com.coursework.lms.core.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt

/** An authenticated request whose principal is the [CurrentUser] read from its access token. */
class CurrentUserAuthentication(
    val currentUser: CurrentUser,
    private val token: Jwt,
    authorities: Collection<GrantedAuthority>,
) : AbstractAuthenticationToken(authorities) {

    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): CurrentUser {
        return currentUser
    }

    override fun getCredentials(): Jwt {
        return token
    }

    override fun getName(): String {
        return currentUser.id.toString()
    }
}
