package com.coursework.lms.core.paging

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/** Which page of a list the client asks for: zero-based [page] of [size] items. */
data class PageQuery(
    @Min(0)
    val page: Int = 0,
    @Min(1)
    @Max(MAX_SIZE.toLong())
    val size: Int = DEFAULT_SIZE,
) {

    /** How many items precede this page. */
    val offset: Long
        get() = page.toLong() * size

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100
    }
}
