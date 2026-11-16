package com.coursework.lms.identity.admin

import com.coursework.lms.identity.validation.PolytechnicEmail
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class UserSummaryResponse(
    @field:Schema(example = "2")
    val id: Int,
    @field:Schema(example = "anna.petrosyan@polytechnic.am")
    val email: String,
    @field:Schema(example = "Anna Petrosyan")
    val fullName: String,
    @field:Schema(allowableValues = ["student", "teacher", "admin"], example = "student")
    val role: String,
    @field:Schema(example = "2026-04-08T11:57:48Z")
    val registeredAt: Instant,
)

data class ChangeRoleRequest(
    @field:Schema(allowableValues = ["student", "teacher", "admin"], example = "teacher")
    val role: String,
)

data class AddTeacherEmailRequest(
    @field:PolytechnicEmail
    @field:Schema(example = "anna.petrosyan@polytechnic.am")
    val email: String,
)

data class TeacherEmailResponse(
    @field:Schema(example = "3")
    val id: Int,
    @field:Schema(example = "anna.petrosyan@polytechnic.am")
    val email: String,
    @field:Schema(description = "The account registered under this email; null until someone signs up with it")
    val account: UserSummaryResponse?,
)
