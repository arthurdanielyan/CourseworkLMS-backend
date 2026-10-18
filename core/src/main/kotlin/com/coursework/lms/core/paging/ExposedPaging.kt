package com.coursework.lms.core.paging

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.Query

/** Runs this query for one page of [query], counting the whole result for the page metadata. */
fun <T> Query.toPage(
    query: PageQuery,
    transform: (ResultRow) -> T,
): PageResponse<T> {
    // The count runs on a copy, before limit/offset narrow this query down to the page.
    val totalElements = copy().count()
    val content = limit(query.size)
        .offset(query.offset)
        .map(transform)
    return PageResponse.of(
        content = content,
        query = query,
        totalElements = totalElements,
    )
}
