package com.coursework.lms.catalog.book

import com.coursework.lms.catalog.NamedItemResponseMapper
import org.springframework.stereotype.Component

@Component
class BookDetailsResponseMapper(
    private val namedItemResponseMapper: NamedItemResponseMapper,
) {

    fun map(from: BookDetails): BookDetailsResponse {
        return BookDetailsResponse(
            id = from.id,
            title = from.title,
            subtitle = from.subtitle,
            description = from.description,
            authors = from.authors.map(namedItemResponseMapper::map),
            publisher = namedItemResponseMapper.map(from.publisher),
            language = namedItemResponseMapper.map(from.language),
            categories = from.categories.map(namedItemResponseMapper::map),
            publicationYear = from.publicationYear,
            edition = from.edition,
            coverImageUrl = from.coverImageUrl,
            pdfUrl = from.pdfUrl,
            totalCopies = from.totalCopies,
            copiesAvailable = from.copiesAvailable,
            maxLoanDays = from.maxLoanDays,
            available = from.available,
            rating = roundRating(from.rating),
            owner = from.owner?.let(namedItemResponseMapper::map),
            favourite = from.favourite,
        )
    }
}
