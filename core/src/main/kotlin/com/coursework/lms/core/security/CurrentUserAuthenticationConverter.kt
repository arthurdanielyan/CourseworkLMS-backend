package com.coursework.lms.core.security

import org.springframework.core.convert.converter.Converter
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component

/**
 * Turns a validated access token into a [CurrentUserAuthentication]: each permission becomes an authority
 * of the same name, and the role becomes `ROLE_<role>`.
 */
@Component
class CurrentUserAuthenticationConverter : Converter<Jwt, CurrentUserAuthentication> {

    override fun convert(source: Jwt): CurrentUserAuthentication {
        val currentUser = CurrentUser(
            id = source.subject?.toIntOrNull() ?: throw invalidToken("subject is not a user id"),
            role = source.getClaimAsString(AccessTokenClaims.ROLE) ?: throw invalidToken("role claim is missing"),
            permissions = source.getClaimAsStringList(AccessTokenClaims.PERMISSIONS).orEmpty().toSet(),
        )
        val authorities = currentUser.permissions.map(::SimpleGrantedAuthority) +
            SimpleGrantedAuthority("ROLE_${currentUser.role}")
        return CurrentUserAuthentication(
            currentUser = currentUser,
            token = source,
            authorities = authorities,
        )
    }

    private fun invalidToken(reason: String): OAuth2AuthenticationException {
        // Thrown as an authentication failure so a malformed token answers 401, which makes the client refresh.
        return OAuth2AuthenticationException(OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, reason, null))
    }
}
