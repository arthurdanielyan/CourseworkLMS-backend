package com.coursework.lms.identity.session

import org.springframework.stereotype.Component

@Component
class TokenPairResponseMapper {

    fun map(from: TokenPair): TokenPairResponse {
        return TokenPairResponse(
            accessToken = from.accessToken,
            refreshToken = from.refreshToken,
        )
    }
}
