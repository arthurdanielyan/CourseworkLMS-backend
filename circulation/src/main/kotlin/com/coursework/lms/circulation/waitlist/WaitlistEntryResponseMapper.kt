package com.coursework.lms.circulation.waitlist

import com.coursework.lms.circulation.reservation.BookRefResponseMapper
import org.springframework.stereotype.Component

@Component
class WaitlistEntryResponseMapper(
    private val bookRefResponseMapper: BookRefResponseMapper,
) {

    fun map(from: WaitlistEntry): WaitlistEntryResponse {
        return WaitlistEntryResponse(
            book = bookRefResponseMapper.map(from.book),
            addedAt = from.addedAt.toInstant(),
            position = from.position,
            offerExpiresAt = from.offerExpiresAt?.toInstant(),
        )
    }
}
