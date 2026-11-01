package com.coursework.lms.identity

/** The form every email address is stored and compared in. */
fun normalizeEmail(email: String): String {
    return email.trim().lowercase()
}
