package com.coursework.lms.notifications

import com.coursework.lms.circulation.reservation.BookRefResponseMapper
import org.springframework.stereotype.Component

@Component
class NotificationResponseMapper(
    private val bookRefResponseMapper: BookRefResponseMapper,
) {

    fun map(from: Notification): NotificationResponse {
        return NotificationResponse(
            id = from.id,
            type = from.kind.value,
            title = from.title,
            message = from.message,
            book = from.book?.let(bookRefResponseMapper::map),
            read = from.read,
            createdAt = from.createdAt.toInstant(),
        )
    }
}
