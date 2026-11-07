package com.coursework.lms.identity.account

import com.coursework.lms.identity.validation.StrongPassword
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class Account(
    val id: Int,
    val email: String,
    val fullName: String,
    val role: String,
    val permissions: Set<String>,
)

data class RenameRequest(
    @NotBlank
    @Size(min = 2, max = 100)
    @field:Schema(example = "Anna Petrosyan")
    val fullName: String,
)

data class ChangePasswordRequest(
    @NotBlank
    @field:Schema(example = "Library#2026")
    val currentPassword: String,
    @field:StrongPassword
    @field:Schema(example = "Library#2027")
    val newPassword: String,
)

data class AccountResponse(
    @field:Schema(example = "2")
    val id: Int,
    @field:Schema(example = "anna.petrosyan@polytechnic.am")
    val email: String,
    @field:Schema(example = "Anna Petrosyan")
    val fullName: String,
    @field:Schema(allowableValues = ["student", "teacher", "admin"], example = "student")
    val role: String,
    @field:ArraySchema(
        arraySchema = Schema(description = "What the user may do, for showing or hiding actions"),
        schema = Schema(example = "reservations.create"),
    )
    val permissions: List<String>,
)
