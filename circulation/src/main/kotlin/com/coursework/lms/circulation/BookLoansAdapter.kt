package com.coursework.lms.circulation

import com.coursework.lms.catalog.book.BookLoans
import com.coursework.lms.circulation.db.ReservationsTable
import com.coursework.lms.circulation.db.WaitlistTable
import com.coursework.lms.circulation.reservation.ReservationStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime

@Component
class BookLoansAdapter(
    private val clock: Clock,
) : BookLoans {

    override fun hasBorrowedCopies(bookId: Int): Boolean {
        return ReservationsTable
            .select(ReservationsTable.id)
            .where {
                (ReservationsTable.bookId eq bookId) and
                    (ReservationsTable.status eq ReservationStatus.BORROWED.value)
            }
            .any()
    }

    override fun withdrawHolds(bookId: Int) {
        // The Waitlist goes first, so the cancelled Reservations' copies aren't offered to anyone.
        WaitlistTable.deleteWhere { WaitlistTable.bookId eq bookId }
        ReservationsTable.update({
            (ReservationsTable.bookId eq bookId) and (ReservationsTable.status eq ReservationStatus.RESERVED.value)
        }) {
            it[status] = ReservationStatus.CANCELLED.value
            it[finishedAt] = OffsetDateTime.now(clock)
        }
    }
}
