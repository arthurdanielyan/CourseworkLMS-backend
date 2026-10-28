package com.coursework.lms.circulation.waitlist

import com.coursework.lms.circulation.reservation.BookRefResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class WaitlistEntryResponse(
    val book: BookRefResponse,
    @field:Schema(example = "2026-11-15T10:00:00Z")
    val addedAt: Instant,
    @field:Schema(description = "Place in line, 1 being next; null while the user holds an Offer", example = "2")
    val position: Int?,
    @field:Schema(
        description = "Set while a copy is held for the user: reserve the book before this time, or it goes to the next in line",
        example = "2026-11-16T10:00:00Z",
    )
    val offerExpiresAt: Instant?,
)
