package com.coursework.lms.identity.validation

import jakarta.validation.Constraint
import jakarta.validation.Payload
import jakarta.validation.ReportAsSingleViolation
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kotlin.reflect.KClass

/** An email address of at most 255 characters issued by the Polytechnic, in any letter case. */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [])
@ReportAsSingleViolation
@NotBlank
@Email
@Size(max = 255)
@Pattern(regexp = "^[^@\\s]+@polytechnic\\.am$", flags = [Pattern.Flag.CASE_INSENSITIVE])
annotation class PolytechnicEmail(
    val message: String = "must be an @polytechnic.am email address",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)
