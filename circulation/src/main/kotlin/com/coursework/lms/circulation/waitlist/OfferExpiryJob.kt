package com.coursework.lms.circulation.waitlist

import com.coursework.lms.core.db.DbUserContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Drops Offers left unanswered for 24 hours, passing each held copy to the next user in line. */
@Component
class OfferExpiryJob(
    private val waitlistService: WaitlistService,
    private val dbUserContext: DbUserContext,
) {

    private val log = LoggerFactory.getLogger(OfferExpiryJob::class.java)

    @Scheduled(
        initialDelayString = "\${lms.circulation.expiry-check-interval:PT5M}",
        fixedDelayString = "\${lms.circulation.expiry-check-interval:PT5M}",
    )
    fun run() {
        val expired = waitlistService.expiredOffers()
        // As with reservations, the database lets only the entry's own user or an admin remove it.
        expired.forEach { (entryId, userId) ->
            dbUserContext.runAs(userId) { waitlistService.dropExpiredOffer(entryId) }
        }
        if (expired.isNotEmpty()) {
            log.info("Dropped {} expired offers", expired.size)
        }
    }
}
