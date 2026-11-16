package com.coursework.lms.circulation.reservation

import com.coursework.lms.catalog.NamedItemResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate

data class ReserveRequest(
    @field:Schema(description = "After today and at most the book's max_loan_days from today", example = "2026-11-29")
    val returnDeadline: LocalDate,
)

data class BookRefResponse(
    @field:Schema(example = "1")
    val id: Int,
    @field:Schema(example = "Clean Code")
    val title: String,
    @field:Schema(example = "https://covers.openlibrary.org/b/id/8430006-L.jpg")
    val coverImageUrl: String?,
)

data class ReservationResponse(
    @field:Schema(example = "17")
    val id: Int,
    val book: BookRefResponse,
    @field:Schema(example = """{"id": 2, "name": "Anna Petrosyan"}""")
    val user: NamedItemResponse,
    @field:Schema(
        description = "reserved → borrowed → returned; a reserved one can also become cancelled or expired",
        allowableValues = ["reserved", "borrowed", "returned", "cancelled", "expired"],
        example = "reserved",
    )
    val status: String,
    @field:Schema(example = "2026-11-15T10:00:00Z")
    val reservedAt: Instant,
    @field:Schema(description = "When an uncollected copy goes back on the shelf; set only while reserved", example = "2026-11-17T10:00:00Z")
    val pickupDeadline: Instant?,
    @field:Schema(example = "2026-11-16T09:30:00Z")
    val borrowedAt: Instant?,
    @field:Schema(description = "When it was returned, cancelled or expired", example = "2026-11-25T12:00:00Z")
    val finishedAt: Instant?,
    @field:Schema(example = "2026-11-29")
    val returnDeadline: LocalDate,
    @field:Schema(description = "Borrowed and past its return deadline", example = "false")
    val overdue: Boolean,
)
