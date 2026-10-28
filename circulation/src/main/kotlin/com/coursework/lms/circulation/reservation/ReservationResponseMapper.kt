package com.coursework.lms.circulation.reservation

import com.coursework.lms.catalog.NamedItemResponseMapper
import org.springframework.stereotype.Component

@Component
class ReservationResponseMapper(
    private val namedItemResponseMapper: NamedItemResponseMapper,
    private val bookRefResponseMapper: BookRefResponseMapper,
) {

    fun map(from: Reservation): ReservationResponse {
        return ReservationResponse(
            id = from.id,
            book = bookRefResponseMapper.map(from.book),
            user = namedItemResponseMapper.map(from.user),
            status = from.status.value,
            reservedAt = from.reservedAt.toInstant(),
            pickupDeadline = from.pickupDeadline?.toInstant(),
            borrowedAt = from.borrowedAt?.toInstant(),
            finishedAt = from.finishedAt?.toInstant(),
            returnDeadline = from.returnDeadline,
            overdue = from.overdue,
        )
    }
}
