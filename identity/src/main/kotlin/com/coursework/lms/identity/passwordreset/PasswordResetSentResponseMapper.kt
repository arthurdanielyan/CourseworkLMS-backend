package com.coursework.lms.identity.passwordreset

import org.springframework.stereotype.Component

@Component
class PasswordResetSentResponseMapper {

    fun map(from: PasswordResetSent): PasswordResetSentResponse {
        return PasswordResetSentResponse(
            email = from.email,
            codeExpiresAt = from.codeExpiresAt,
            resendAvailableAt = from.resendAvailableAt,
        )
    }
}
