package com.coursework.lms.circulation.reservation

import com.coursework.lms.catalog.NamedItem
import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.circulation.CirculationErrorCode
import com.coursework.lms.circulation.CirculationProperties
import com.coursework.lms.circulation.LIBRARY_ZONE
import com.coursework.lms.circulation.db.ReservationsTable
import com.coursework.lms.circulation.db.WaitlistTable
import com.coursework.lms.circulation.db.hasActiveReservation
import com.coursework.lms.core.error.ApiException
import com.coursework.lms.core.error.ErrorCode
import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import com.coursework.lms.core.paging.toPage
import com.coursework.lms.identity.db.UsersTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.compoundAnd
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

@Service
class ReservationService(
    private val clock: Clock,
    private val properties: CirculationProperties,
) {

    @Transactional
    fun reserve(
        bookId: Int,
        userId: Int,
        returnDeadline: LocalDate,
    ): Reservation {
        // Locks the book, so two people can't both take its last copy.
        val book = BooksTable
            .select(BooksTable.copiesAvailable, BooksTable.maxLoanDays)
            .where { BooksTable.id eq bookId }
            .forUpdate()
            .singleOrNull()
            ?: throw notFound("There is no book with id $bookId.")
        if (hasActiveReservation(userId, bookId)) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = CirculationErrorCode.ALREADY_RESERVED,
                detail = "You already have this book reserved or borrowed.",
            )
        }
        // A held copy isn't counted as available, but the user it's offered to may take it.
        if (book[BooksTable.copiesAvailable] <= 0 && !hasOpenOffer(userId, bookId)) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = CirculationErrorCode.NO_COPIES_AVAILABLE,
                detail = "No copies of this book are available right now.",
            )
        }
        val today = today()
        val latest = today.plusDays(book[BooksTable.maxLoanDays].toLong())
        if (!returnDeadline.isAfter(today) || returnDeadline.isAfter(latest)) {
            throw ApiException(
                status = HttpStatus.BAD_REQUEST,
                code = CirculationErrorCode.INVALID_RETURN_DATE,
                detail = "Choose a return date from ${today.plusDays(1)} to $latest.",
            )
        }
        val id = ReservationsTable.insert {
            it[ReservationsTable.userId] = userId
            it[ReservationsTable.bookId] = bookId
            it[ReservationsTable.returnDeadline] = returnDeadline
            it[ReservationsTable.createdAt] = OffsetDateTime.now(clock)
        } get ReservationsTable.id
        return find(id)
    }

    /** The user's own Reservations, the newest first. */
    @Transactional(readOnly = true)
    fun mine(
        userId: Int,
        statuses: List<ReservationStatus>,
        pageQuery: PageQuery,
    ): PageResponse<Reservation> {
        return list(ReservationFilter(statuses = statuses, userId = userId), pageQuery)
    }

    /** Everyone's Reservations matching [filter], the newest first. */
    @Transactional(readOnly = true)
    fun list(
        filter: ReservationFilter,
        pageQuery: PageQuery,
    ): PageResponse<Reservation> {
        val today = today()
        return reservationQuery()
            .where { matching(filter) }
            .orderBy(ReservationsTable.createdAt to SortOrder.DESC, ReservationsTable.id to SortOrder.DESC)
            .toPage(pageQuery) { toReservation(it, today) }
    }

    /** Cancels one of the user's own Reserved Reservations. */
    @Transactional
    fun cancel(
        reservationId: Int,
        userId: Int,
    ): Reservation {
        val reservation = find(reservationId)
        if (reservation.user.id != userId) {
            throw notFound("There is no reservation with id $reservationId.")
        }
        return move(reservation, from = ReservationStatus.RESERVED, to = ReservationStatus.CANCELLED)
    }

    /** Marks a Reserved copy as handed over to its user. */
    @Transactional
    fun handOver(reservationId: Int): Reservation {
        return move(find(reservationId), from = ReservationStatus.RESERVED, to = ReservationStatus.BORROWED)
    }

    /** Marks a Borrowed copy as back in the library. */
    @Transactional
    fun takeBack(reservationId: Int): Reservation {
        return move(find(reservationId), from = ReservationStatus.BORROWED, to = ReservationStatus.RETURNED)
    }

    /** The Reserved Reservations whose Pickup Window has passed, as their ids and their users' ids. */
    @Transactional(readOnly = true)
    fun pickupWindowPassed(): List<Pair<Int, Int>> {
        val cutoff = OffsetDateTime.now(clock).minus(properties.pickupWindow)
        return ReservationsTable
            .select(ReservationsTable.id, ReservationsTable.userId)
            .where {
                (ReservationsTable.status eq ReservationStatus.RESERVED.value) and
                    (ReservationsTable.createdAt less cutoff)
            }
            .map { it[ReservationsTable.id] to it[ReservationsTable.userId] }
    }

    /** Expires a Reserved Reservation whose Pickup Window has passed; anything else is left as it is. */
    @Transactional
    fun expire(reservationId: Int) {
        val cutoff = OffsetDateTime.now(clock).minus(properties.pickupWindow)
        ReservationsTable.update({
            (ReservationsTable.id eq reservationId) and
                (ReservationsTable.status eq ReservationStatus.RESERVED.value) and
                (ReservationsTable.createdAt less cutoff)
        }) {
            it[status] = ReservationStatus.EXPIRED.value
            it[finishedAt] = OffsetDateTime.now(clock)
        }
    }

    private fun move(
        reservation: Reservation,
        from: ReservationStatus,
        to: ReservationStatus,
    ): Reservation {
        if (reservation.status != from) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = CirculationErrorCode.INVALID_STATUS,
                detail = "This reservation is ${reservation.status.value}, so it cannot become ${to.value}.",
            )
        }
        val now = OffsetDateTime.now(clock)
        ReservationsTable.update({ ReservationsTable.id eq reservation.id }) {
            it[status] = to.value
            if (to == ReservationStatus.BORROWED) {
                it[borrowedAt] = now
            } else {
                it[finishedAt] = now
            }
        }
        return find(reservation.id)
    }

    private fun hasOpenOffer(
        userId: Int,
        bookId: Int,
    ): Boolean {
        return WaitlistTable
            .select(WaitlistTable.id)
            .where {
                (WaitlistTable.userId eq userId) and
                    (WaitlistTable.bookId eq bookId) and
                    (WaitlistTable.offerExpiresAt greater OffsetDateTime.now(clock))
            }
            .any()
    }

    private fun find(reservationId: Int): Reservation {
        return reservationQuery()
            .where { ReservationsTable.id eq reservationId }
            .singleOrNull()
            ?.let { toReservation(it, today()) }
            ?: throw notFound("There is no reservation with id $reservationId.")
    }

    private fun matching(filter: ReservationFilter): Op<Boolean> {
        val criteria = buildList {
            if (filter.statuses.isNotEmpty()) {
                add(ReservationsTable.status inList filter.statuses.map(ReservationStatus::value))
            }
            if (filter.overdue) {
                add(ReservationsTable.status eq ReservationStatus.BORROWED.value)
                add(ReservationsTable.returnDeadline less today())
            }
            filter.userId?.let { add(ReservationsTable.userId eq it) }
            filter.bookId?.let { add(ReservationsTable.bookId eq it) }
        }
        return if (criteria.isEmpty()) Op.TRUE else criteria.compoundAnd()
    }

    private fun reservationQuery(): Query {
        return ReservationsTable
            .join(BooksTable, JoinType.INNER, ReservationsTable.bookId, BooksTable.id)
            .join(UsersTable, JoinType.INNER, ReservationsTable.userId, UsersTable.id)
            .select(ReservationsTable.columns + listOf(BooksTable.title, BooksTable.coverImageUrl, UsersTable.username))
    }

    private fun toReservation(
        row: ResultRow,
        today: LocalDate,
    ): Reservation {
        val status = checkNotNull(ReservationStatus.fromValue(row[ReservationsTable.status]))
        val reservedAt = row[ReservationsTable.createdAt]
        return Reservation(
            id = row[ReservationsTable.id],
            book = BookRef(
                id = row[ReservationsTable.bookId],
                title = row[BooksTable.title],
                coverImageUrl = row[BooksTable.coverImageUrl],
            ),
            user = NamedItem(row[ReservationsTable.userId], row[UsersTable.username]),
            status = status,
            reservedAt = reservedAt,
            pickupDeadline = if (status == ReservationStatus.RESERVED) reservedAt.plus(properties.pickupWindow) else null,
            borrowedAt = row[ReservationsTable.borrowedAt],
            finishedAt = row[ReservationsTable.finishedAt],
            returnDeadline = row[ReservationsTable.returnDeadline],
            overdue = status == ReservationStatus.BORROWED && row[ReservationsTable.returnDeadline].isBefore(today),
        )
    }

    private fun today(): LocalDate {
        return LocalDate.ofInstant(clock.instant(), LIBRARY_ZONE)
    }

    private fun notFound(detail: String): ApiException {
        return ApiException(
            status = HttpStatus.NOT_FOUND,
            code = ErrorCode.NOT_FOUND,
            detail = detail,
        )
    }
}
