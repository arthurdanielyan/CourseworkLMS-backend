package com.coursework.lms.circulation.reservation

import com.coursework.lms.catalog.NamedItem
import java.time.LocalDate
import java.time.OffsetDateTime

/** A Reservation's stage, stored and sent as [value]. */
enum class ReservationStatus(
    val value: String,
) {

    RESERVED("reserved"),
    BORROWED("borrowed"),
    RETURNED("returned"),
    CANCELLED("cancelled"),
    EXPIRED("expired"),
    ;

    companion object {

        /** The statuses that hold a copy of the book. */
        val active: List<ReservationStatus> = listOf(RESERVED, BORROWED)

        fun fromValue(value: String): ReservationStatus? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

data class BookRef(
    val id: Int,
    val title: String,
    val coverImageUrl: String?,
)

/** [pickupDeadline] is set only while the Reservation is Reserved. */
data class Reservation(
    val id: Int,
    val book: BookRef,
    val user: NamedItem,
    val status: ReservationStatus,
    val reservedAt: OffsetDateTime,
    val pickupDeadline: OffsetDateTime?,
    val borrowedAt: OffsetDateTime?,
    val finishedAt: OffsetDateTime?,
    val returnDeadline: LocalDate,
    val overdue: Boolean,
)

/** Which Reservations a list shows; empty criteria match all of them. */
data class ReservationFilter(
    val statuses: List<ReservationStatus> = emptyList(),
    val overdue: Boolean = false,
    val userId: Int? = null,
    val bookId: Int? = null,
)
