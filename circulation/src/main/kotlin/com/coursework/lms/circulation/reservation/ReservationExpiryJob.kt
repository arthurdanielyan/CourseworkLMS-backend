package com.coursework.lms.circulation.reservation

import com.coursework.lms.core.db.DbUserContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Expires Reserved Reservations nobody picked up within the Pickup Window, putting their copies back. */
@Component
class ReservationExpiryJob(
    private val reservationService: ReservationService,
    private val dbUserContext: DbUserContext,
) {

    private val log = LoggerFactory.getLogger(ReservationExpiryJob::class.java)

    @Scheduled(
        initialDelayString = "\${lms.circulation.expiry-check-interval:PT5M}",
        fixedDelayString = "\${lms.circulation.expiry-check-interval:PT5M}",
    )
    fun run() {
        val expired = reservationService.pickupWindowPassed()
        // Each one runs as its own user: the database lets only them or an admin end a reservation.
        expired.forEach { (reservationId, userId) ->
            dbUserContext.runAs(userId) { reservationService.expire(reservationId) }
        }
        if (expired.isNotEmpty()) {
            log.info("Expired {} reservations", expired.size)
        }
    }
}
