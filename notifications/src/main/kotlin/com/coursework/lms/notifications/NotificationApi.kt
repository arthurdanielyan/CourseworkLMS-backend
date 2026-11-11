package com.coursework.lms.notifications

import com.coursework.lms.circulation.reservation.BookRefResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class NotificationResponse(
    @field:Schema(example = "41")
    val id: Int,
    @field:Schema(
        allowableValues = ["waitlist_offer", "pickup_reminder", "reservation_expired", "return_reminder", "overdue"],
        example = "waitlist_offer",
    )
    val type: String,
    @field:Schema(example = "A copy of “Clean Code” is waiting for you")
    val title: String,
    @field:Schema(
        example = "It is held for you until 17 November, 14:30. " +
            "Reserve it in the app before then, or it goes to the next person in line.",
    )
    val message: String,
    @field:Schema(description = "The book it is about, for opening Book Details")
    val book: BookRefResponse?,
    @field:Schema(example = "false")
    val read: Boolean,
    @field:Schema(example = "2026-11-16T10:30:00Z")
    val createdAt: Instant,
)

data class UnreadCountResponse(
    @field:Schema(example = "2")
    val count: Long,
)
