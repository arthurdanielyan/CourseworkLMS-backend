package com.coursework.lms.support

import com.coursework.lms.core.security.AccessTokenIssuer
import com.coursework.lms.core.security.CurrentUser
import com.coursework.lms.core.security.JwtProperties
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/** Access tokens for tests: valid ones from the real issuer, and deliberately broken ones. */
@Component
class TestTokens(
    private val issuer: AccessTokenIssuer,
    private val encoder: JwtEncoder,
    private val properties: JwtProperties,
) {

    fun bearer(user: CurrentUser): String {
        return "Bearer ${issuer.issue(user).value}"
    }

    fun expiredBearer(user: CurrentUser): String {
        val issuedAt = Instant.now().minus(Duration.ofHours(2))
        return bearerOf(
            JwtClaimsSet.builder()
                .issuer(properties.issuer)
                .subject(user.id.toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
                .claim("role", user.role)
                .build(),
        )
    }

    fun foreignIssuerBearer(user: CurrentUser): String {
        return bearerOf(
            JwtClaimsSet.builder()
                .issuer("someone-else")
                .subject(user.id.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofMinutes(15)))
                .claim("role", user.role)
                .build(),
        )
    }

    private fun bearerOf(claims: JwtClaimsSet): String {
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return "Bearer ${encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue}"
    }
}
