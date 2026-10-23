package com.coursework.lms.catalog.book

import com.coursework.lms.catalog.NamedItemResponse
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema

data class BookSummaryResponse(
    @field:Schema(example = "1")
    val id: Int,
    @field:Schema(example = "Clean Code")
    val title: String,
    @field:Schema(example = "A Handbook of Agile Software Craftsmanship")
    val subtitle: String?,
    @field:ArraySchema(schema = Schema(example = "Robert C. Martin"))
    val authors: List<String>,
    @field:Schema(example = "Pearson")
    val publisher: String,
    @field:Schema(example = "https://covers.openlibrary.org/b/id/8430006-L.jpg")
    val coverImageUrl: String?,
    @field:Schema(example = "true")
    val hasPdf: Boolean,
    @field:Schema(description = "Average stars, rounded to one decimal; null while the book has no reviews", example = "4.7")
    val rating: Double?,
    @field:Schema(description = "Whether at least one copy can be reserved now", example = "true")
    val available: Boolean,
)

data class BookDetailsResponse(
    @field:Schema(example = "1")
    val id: Int,
    @field:Schema(example = "Clean Code")
    val title: String,
    @field:Schema(example = "A Handbook of Agile Software Craftsmanship")
    val subtitle: String?,
    @field:Schema(example = "Practical advice on writing readable, maintainable code.")
    val description: String,
    val authors: List<NamedItemResponse>,
    val publisher: NamedItemResponse,
    val language: NamedItemResponse,
    val categories: List<NamedItemResponse>,
    @field:Schema(example = "2008")
    val publicationYear: Int?,
    @field:Schema(example = "1st edition")
    val edition: String?,
    @field:Schema(example = "https://covers.openlibrary.org/b/id/8430006-L.jpg")
    val coverImageUrl: String?,
    @field:Schema(description = "Download link; null when the book has no PDF", example = "https://archive.org/download/opensource/library_resource_21.pdf")
    val pdfUrl: String?,
    @field:Schema(example = "6")
    val totalCopies: Int,
    @field:Schema(example = "3")
    val copiesAvailable: Int,
    @field:Schema(description = "Whether at least one copy can be reserved now", example = "true")
    val available: Boolean,
    @field:Schema(description = "Average stars, rounded to one decimal; null while the book has no reviews", example = "4.7")
    val rating: Double?,
    @field:Schema(description = "The teacher who owns the book; null for a University Book")
    val owner: NamedItemResponse?,
)
