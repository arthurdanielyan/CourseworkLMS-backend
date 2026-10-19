package com.coursework.lms.identity.registration

import org.springframework.stereotype.Component

@Component
class PendingRegistrationResponseMapper {

    fun map(from: PendingRegistration): PendingRegistrationResponse {
        return PendingRegistrationResponse(
            email = from.email,
            codeExpiresAt = from.codeExpiresAt,
            resendAvailableAt = from.resendAvailableAt,
        )
    }
}
