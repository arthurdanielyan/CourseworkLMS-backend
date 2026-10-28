package com.coursework.lms.circulation.db

import com.coursework.lms.circulation.reservation.ReservationStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select

/** Whether the user has the book Reserved or Borrowed. Call it inside a transaction. */
internal fun hasActiveReservation(
    userId: Int,
    bookId: Int,
): Boolean {
    return ReservationsTable
        .select(ReservationsTable.id)
        .where {
            (ReservationsTable.userId eq userId) and
                (ReservationsTable.bookId eq bookId) and
                (ReservationsTable.status inList ReservationStatus.active.map(ReservationStatus::value))
        }
        .any()
}
