package com.coursework.lms.identity

/** Values of a problem response's `code` that only account access answers with. */
object IdentityErrorCode {

    const val EMAIL_TAKEN = "email_taken"
    const val REGISTRATION_NOT_FOUND = "registration_not_found"
    const val INVALID_CODE = "invalid_code"
    const val CODE_EXPIRED = "code_expired"
    const val TOO_MANY_ATTEMPTS = "too_many_attempts"
    const val TOO_MANY_REQUESTS = "too_many_requests"
    const val INVALID_CREDENTIALS = "invalid_credentials"
    const val INVALID_REFRESH_TOKEN = "invalid_refresh_token"
}
