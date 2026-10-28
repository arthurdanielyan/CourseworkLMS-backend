package com.coursework.lms.catalog.book

import com.coursework.lms.catalog.NamedItem

/** Empty criteria match every book. [ownerIds] and [universityBooks] are one criterion: a book matching either counts. */
data class BookSearch(
    val text: String? = null,
    val author: String? = null,
    val publicationYear: Int? = null,
    val categoryIds: List<Int> = emptyList(),
    val languageIds: List<Int> = emptyList(),
    val available: Boolean? = null,
    val ownerIds: List<Int> = emptyList(),
    val universityBooks: Boolean = false,
)

data class BookSummary(
    val id: Int,
    val title: String,
    val subtitle: String?,
    val authors: List<String>,
    val publisher: String,
    val coverImageUrl: String?,
    val hasPdf: Boolean,
    val rating: Float?,
    val available: Boolean,
    val favourite: Boolean,
)

/** [owner] is null for a University Book. */
data class BookDetails(
    val id: Int,
    val title: String,
    val subtitle: String?,
    val description: String,
    val authors: List<NamedItem>,
    val publisher: NamedItem,
    val language: NamedItem,
    val categories: List<NamedItem>,
    val publicationYear: Int?,
    val edition: String?,
    val coverImageUrl: String?,
    val pdfUrl: String?,
    val totalCopies: Int,
    val copiesAvailable: Int,
    val maxLoanDays: Int,
    val rating: Float?,
    val owner: NamedItem?,
    val favourite: Boolean,
) {

    val available: Boolean
        get() = copiesAvailable > 0
}
