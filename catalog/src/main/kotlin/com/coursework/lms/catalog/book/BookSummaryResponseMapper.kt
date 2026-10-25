package com.coursework.lms.catalog.book

import org.springframework.stereotype.Component

@Component
class BookSummaryResponseMapper {

    fun map(from: BookSummary): BookSummaryResponse {
        return BookSummaryResponse(
            id = from.id,
            title = from.title,
            subtitle = from.subtitle,
            authors = from.authors,
            publisher = from.publisher,
            coverImageUrl = from.coverImageUrl,
            hasPdf = from.hasPdf,
            rating = roundRating(from.rating),
            available = from.available,
            favourite = from.favourite,
        )
    }
}
