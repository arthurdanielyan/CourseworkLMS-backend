package com.coursework.lms.catalog.book

import org.springframework.stereotype.Component

@Component
class BookFormMapper {

    fun map(from: BookRequest): BookForm {
        return BookForm(
            title = from.title.trim(),
            subtitle = from.subtitle.trimToNull(),
            description = from.description.trim(),
            authors = from.authors.mapNotNull { it.trimToNull() },
            publisher = from.publisher.trim(),
            languageId = from.languageId,
            categoryIds = from.categoryIds,
            publicationYear = from.publicationYear,
            edition = from.edition.trimToNull(),
            totalCopies = from.totalCopies,
            maxLoanDays = from.maxLoanDays,
        )
    }

    private fun String?.trimToNull(): String? {
        return this?.trim()?.takeIf { it.isNotEmpty() }
    }
}
