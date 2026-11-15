package com.coursework.lms.identity.admin

import org.springframework.stereotype.Component

@Component
class UserSummaryResponseMapper {

    fun map(from: UserSummary): UserSummaryResponse {
        return UserSummaryResponse(
            id = from.id,
            email = from.email,
            fullName = from.fullName,
            role = from.role,
            registeredAt = from.registeredAt.toInstant(),
        )
    }
}

@Component
class TeacherEmailResponseMapper(
    private val userSummaryResponseMapper: UserSummaryResponseMapper,
) {

    fun map(from: TeacherEmail): TeacherEmailResponse {
        return TeacherEmailResponse(
            id = from.id,
            email = from.email,
            account = from.account?.let(userSummaryResponseMapper::map),
        )
    }
}
