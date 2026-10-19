package com.coursework.lms.identity.session

import com.coursework.lms.core.security.AccessTokenIssuer
import org.springframework.stereotype.Component

/** Starts or renews a login session: an access token for the user's current role and a fresh refresh token. */
@Component
class TokenPairIssuer(
    private val currentUserLoader: CurrentUserLoader,
    private val accessTokenIssuer: AccessTokenIssuer,
    private val refreshTokens: RefreshTokenStore,
) {

    /** Must run inside a transaction, which also stores the refresh token. */
    fun issue(userId: Int): TokenPair {
        return issue(userId, refreshTokens.create(userId))
    }

    /** Pairs an already stored [refreshToken] of [userId] with a new access token. */
    fun issue(
        userId: Int,
        refreshToken: NewRefreshToken,
    ): TokenPair {
        val accessToken = accessTokenIssuer.issue(currentUserLoader.load(userId))
        return TokenPair(
            accessToken = accessToken.value,
            refreshToken = refreshToken.value,
        )
    }
}
