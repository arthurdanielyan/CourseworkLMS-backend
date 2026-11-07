package com.coursework.lms.identity.account

import org.springframework.stereotype.Component

@Component
class AccountResponseMapper {

    fun map(from: Account): AccountResponse {
        return AccountResponse(
            id = from.id,
            email = from.email,
            fullName = from.fullName,
            role = from.role,
            permissions = from.permissions.sorted(),
        )
    }
}
