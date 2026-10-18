package com.coursework.lms.core.paging

/** One page of a list, together with where it sits in the whole list. */
data class PageResponse<T>(
    val content: List<T>,
    val page: PageMetadata,
) {

    fun <R> map(transform: (T) -> R): PageResponse<R> {
        return PageResponse(
            content = content.map(transform),
            page = page,
        )
    }

    companion object {

        fun <T> of(
            content: List<T>,
            query: PageQuery,
            totalElements: Long,
        ): PageResponse<T> {
            return PageResponse(
                content = content,
                page = PageMetadata(
                    size = query.size,
                    number = query.page,
                    totalElements = totalElements,
                    totalPages = ((totalElements + query.size - 1) / query.size).toInt(),
                ),
            )
        }
    }
}

/** The position of a [PageResponse]: its zero-based [number] and [size], and the totals of the whole list. */
data class PageMetadata(
    val size: Int,
    val number: Int,
    val totalElements: Long,
    val totalPages: Int,
)
