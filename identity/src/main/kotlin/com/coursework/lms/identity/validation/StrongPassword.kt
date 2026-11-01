package com.coursework.lms.identity.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * A password of 8 to 72 bytes with an uppercase and a lowercase letter, at least two digits and at least one character
 * that is neither a letter nor a digit.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [StrongPasswordValidator::class])
annotation class StrongPassword(
    val message: String = "must be 8-72 characters with an uppercase and a lowercase letter, 2 digits and a special character",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class StrongPasswordValidator : ConstraintValidator<StrongPassword, String> {

    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean {
        if (value == null) {
            return false
        }
        // BCrypt ignores everything past 72 bytes, so a longer password would silently be cut short.
        val byteLength = value.toByteArray().size
        return value.length >= MIN_LENGTH &&
            byteLength <= MAX_BYTES &&
            value.any(Char::isUpperCase) &&
            value.any(Char::isLowerCase) &&
            value.count(Char::isDigit) >= MIN_DIGITS &&
            value.any { !it.isLetterOrDigit() }
    }

    private companion object {
        const val MIN_LENGTH = 8
        const val MAX_BYTES = 72
        const val MIN_DIGITS = 2
    }
}
