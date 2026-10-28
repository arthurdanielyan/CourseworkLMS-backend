package com.coursework.lms.circulation.waitlist

import com.coursework.lms.circulation.reservation.BookRef
import java.time.OffsetDateTime

/** A place in a book's Waitlist: either a [position] in line, or an Offer that lasts until [offerExpiresAt]. */
data class WaitlistEntry(
    val book: BookRef,
    val addedAt: OffsetDateTime,
    val position: Int?,
    val offerExpiresAt: OffsetDateTime?,
)
