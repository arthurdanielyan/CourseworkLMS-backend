package com.coursework.lms.circulation

import com.coursework.lms.circulation.db.ReservationsTable
import com.coursework.lms.circulation.db.WaitlistTable
import com.coursework.lms.circulation.reservation.ReservationStatus
import com.coursework.lms.core.error.ApiException
import com.coursework.lms.identity.admin.UserDeletionHook
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime

@Component
class CirculationUserDeletionHook(
    private val clock: Clock,
) : UserDeletionHook {

    // The account's rows would vanish with it anyway (ON DELETE CASCADE), but only a direct delete or cancel passes
    // a held Offer or a Reserved copy on to the next user; a cascade just drops it.
    override fun beforeDelete(userId: Int) {
        val borrowing = ReservationsTable.select(ReservationsTable.id)
            .where {
                (ReservationsTable.userId eq userId) and (ReservationsTable.status eq ReservationStatus.BORROWED.value)
            }
            .any()
        if (borrowing) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = CirculationErrorCode.HAS_BORROWED_COPIES,
                detail = "This user still has a borrowed copy. Take it back first.",
            )
        }
        WaitlistTable.deleteWhere { WaitlistTable.userId eq userId }
        ReservationsTable.update({
            (ReservationsTable.userId eq userId) and (ReservationsTable.status eq ReservationStatus.RESERVED.value)
        }) {
            it[status] = ReservationStatus.CANCELLED.value
            it[finishedAt] = OffsetDateTime.now(clock)
        }
    }
}
