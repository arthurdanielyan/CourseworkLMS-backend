package com.coursework.lms.catalog.book

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class BookRequest(
    @NotBlank
    @Size(max = 100)
    @field:Schema(example = "Clean Code")
    val title: String,
    @Size(max = 100)
    @field:Schema(example = "A Handbook of Agile Software Craftsmanship")
    val subtitle: String?,
    @NotBlank
    @field:Schema(example = "Practical advice on writing readable, maintainable code.")
    val description: String,
    @Size(min = 1, max = 10)
    @field:ArraySchema(
        arraySchema = Schema(description = "Names; a name the library doesn't know yet is added"),
        schema = Schema(example = "Robert C. Martin"),
    )
    val authors: List<String>,
    @NotBlank
    @Size(max = 50)
    @field:Schema(description = "A name; one the library doesn't know yet is added", example = "Pearson")
    val publisher: String,
    @field:Schema(example = "1")
    val languageId: Int,
    @field:ArraySchema(schema = Schema(example = "2"))
    val categoryIds: List<Int> = emptyList(),
    @Min(1000)
    @Max(2100)
    @field:Schema(example = "2008")
    val publicationYear: Int?,
    @Size(max = 50)
    @field:Schema(example = "1st edition")
    val edition: String?,
    @Min(0)
    @Max(10000)
    @field:Schema(description = "Admins only; leave it out to keep the current number", example = "6")
    val totalCopies: Int?,
    @Min(1)
    @Max(365)
    @field:Schema(description = "Admins only; leave it out to keep the current length", example = "14")
    val maxLoanDays: Int?,
)
