package com.coursework.lms.core.error

import org.springframework.http.HttpStatus

/**
 * A failure a feature reports to the client as a problem response with [status], [code] and [detail], sent with
 * [headers] such as `Retry-After`.
 */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    val detail: String,
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException(detail)
