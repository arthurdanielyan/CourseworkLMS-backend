package com.coursework.lms.core.security

import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/** A signed access token and the moment it stops being accepted. */
data class IssuedAccessToken(
    val value: String,
    val expiresAt: Instant,
)

/** Signs access tokens in the shape [CurrentUserAuthenticationConverter] reads back. */
@Component
class AccessTokenIssuer(
    private val encoder: JwtEncoder,
    private val properties: JwtProperties,
    private val clock: Clock,
) {

    fun issue(user: CurrentUser): IssuedAccessToken {
        val issuedAt = clock.instant()
        val expiresAt = issuedAt.plus(properties.accessTokenTtl)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .subject(user.id.toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim(AccessTokenClaims.ROLE, user.role)
            .claim(AccessTokenClaims.PERMISSIONS, user.permissions.sorted())
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims))
        return IssuedAccessToken(
            value = token.tokenValue,
            expiresAt = expiresAt,
        )
    }
}
