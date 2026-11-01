package com.coursework.lms.core.error

/**
 * Stable machine-readable values of a problem response's `code` that any module may answer with, which clients switch
 * on. Codes only one feature answers with live in that feature's module.
 */
object ErrorCode {

    const val NOT_AUTHENTICATED = "not_authenticated"
    const val ACCESS_DENIED = "access_denied"
    const val VALIDATION_FAILED = "validation_failed"
    const val BAD_REQUEST = "bad_request"
    const val NOT_FOUND = "not_found"
    const val METHOD_NOT_ALLOWED = "method_not_allowed"
    const val CONFLICT = "conflict"
    const val INTERNAL_ERROR = "internal_error"
}
