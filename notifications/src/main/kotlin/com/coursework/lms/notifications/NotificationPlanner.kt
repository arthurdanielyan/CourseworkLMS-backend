package com.coursework.lms.notifications

import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.circulation.CirculationProperties
import com.coursework.lms.circulation.LIBRARY_ZONE
import com.coursework.lms.circulation.db.ReservationsTable
import com.coursework.lms.circulation.db.WaitlistTable
import com.coursework.lms.circulation.reservation.ReservationStatus
import com.coursework.lms.notifications.db.NotificationsTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Turns the state of reservations and waitlists into the notifications users are due. */
@Component
class NotificationPlanner(
    private val clock: Clock,
    private val properties: NotificationProperties,
    private val circulationProperties: CirculationProperties,
) {

    // Every notification carries a key naming what it is about, so planning again never repeats one.
    @Transactional
    fun planDue() {
        val now = ZonedDateTime.ofInstant(clock.instant(), LIBRARY_ZONE)
        val today = now.toLocalDate()
        planOffers()
        planPickupReminders(now.toOffsetDateTime())
        planExpired(now.toOffsetDateTime())
        if (now.hour >= properties.reminderHour) {
            planReturnReminders(today)
            planOverdue(today)
        }
    }

    private fun planOffers() {
        WaitlistTable
            .join(BooksTable, JoinType.INNER, WaitlistTable.bookId, BooksTable.id)
            .select(WaitlistTable.columns + BooksTable.title)
            .where { WaitlistTable.offeredAt.isNotNull() }
            .forEach { row ->
                val title = row[BooksTable.title]
                add(
                    userId = row[WaitlistTable.userId],
                    bookId = row[WaitlistTable.bookId],
                    kind = NotificationKind.WAITLIST_OFFER,
                    key = "${row[WaitlistTable.id]}:${row[WaitlistTable.offeredAt]?.toEpochSecond()}",
                    title = "A copy of “$title” is waiting for you",
                    message = "It is held for you until ${timeOf(row[WaitlistTable.offerExpiresAt])}. " +
                        "Reserve it in the app before then, or it goes to the next person in line.",
                )
            }
    }

    private fun planPickupReminders(now: OffsetDateTime) {
        val remindAfter = circulationProperties.pickupWindow.minus(properties.pickupReminderBefore)
        reservations(ReservationStatus.RESERVED) { ReservationsTable.createdAt lessEq now.minus(remindAfter) }
            .forEach { row ->
                val title = row[BooksTable.title]
                val deadline = row[ReservationsTable.createdAt].plus(circulationProperties.pickupWindow)
                add(
                    userId = row[ReservationsTable.userId],
                    bookId = row[ReservationsTable.bookId],
                    kind = NotificationKind.PICKUP_REMINDER,
                    key = "${row[ReservationsTable.id]}",
                    title = "Pick up “$title” soon",
                    message = "Your reservation ends at ${timeOf(deadline)} if the copy isn't picked up by then.",
                )
            }
    }

    // Only recent ones: Reservations that expired before notifications existed stay quiet.
    private fun planExpired(now: OffsetDateTime) {
        reservations(ReservationStatus.EXPIRED) { ReservationsTable.finishedAt greaterEq now.minus(RECENT) }
            .forEach { row ->
                val title = row[BooksTable.title]
                add(
                    userId = row[ReservationsTable.userId],
                    bookId = row[ReservationsTable.bookId],
                    kind = NotificationKind.RESERVATION_EXPIRED,
                    key = "${row[ReservationsTable.id]}",
                    title = "Your reservation of “$title” expired",
                    message = "The copy wasn't picked up in time, so it went back to the library.",
                )
            }
    }

    private fun planReturnReminders(today: LocalDate) {
        reservations(ReservationStatus.BORROWED) { ReservationsTable.returnDeadline eq today.plusDays(1) }
            .forEach { row ->
                val title = row[BooksTable.title]
                add(
                    userId = row[ReservationsTable.userId],
                    bookId = row[ReservationsTable.bookId],
                    kind = NotificationKind.RETURN_REMINDER,
                    key = "${row[ReservationsTable.id]}",
                    title = "“$title” is due tomorrow",
                    message = "Please return it to the library by ${dateOf(row[ReservationsTable.returnDeadline])}.",
                )
            }
    }

    private fun planOverdue(today: LocalDate) {
        reservations(ReservationStatus.BORROWED) { ReservationsTable.returnDeadline less today }
            .forEach { row ->
                val title = row[BooksTable.title]
                add(
                    userId = row[ReservationsTable.userId],
                    bookId = row[ReservationsTable.bookId],
                    kind = NotificationKind.OVERDUE,
                    key = "${row[ReservationsTable.id]}",
                    title = "“$title” is overdue",
                    message = "It was due on ${dateOf(row[ReservationsTable.returnDeadline])}. " +
                        "Please return it to the library as soon as you can.",
                )
            }
    }

    private fun reservations(
        status: ReservationStatus,
        condition: () -> Op<Boolean>,
    ): Query {
        return ReservationsTable
            .join(BooksTable, JoinType.INNER, ReservationsTable.bookId, BooksTable.id)
            .select(ReservationsTable.columns + BooksTable.title)
            .where { (ReservationsTable.status eq status.value) and condition() }
    }

    private fun add(
        userId: Int,
        bookId: Int,
        kind: NotificationKind,
        key: String,
        title: String,
        message: String,
    ) {
        NotificationsTable.insertIgnore {
            it[NotificationsTable.userId] = userId
            it[NotificationsTable.bookId] = bookId
            it[type] = kind.value
            it[dedupeKey] = "${kind.value}:$key"
            it[NotificationsTable.title] = title
            it[NotificationsTable.message] = message
            it[createdAt] = OffsetDateTime.now(clock)
        }
    }

    private fun timeOf(moment: OffsetDateTime?): String {
        return moment?.atZoneSameInstant(LIBRARY_ZONE)?.format(TIME) ?: "soon"
    }

    private fun dateOf(date: LocalDate): String {
        return date.format(DATE)
    }

    private companion object {
        val RECENT: Duration = Duration.ofDays(2)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM, HH:mm")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM")
    }
}
