package com.coursework.lms.catalog.book

/** A book as a teacher or admin fills it in; [totalCopies] and [maxLoanDays] are for admins only. */
data class BookForm(
    val title: String,
    val subtitle: String?,
    val description: String,
    val authors: List<String>,
    val publisher: String,
    val languageId: Int,
    val categoryIds: List<Int>,
    val publicationYear: Int?,
    val edition: String?,
    val totalCopies: Int?,
    val maxLoanDays: Int?,
)
