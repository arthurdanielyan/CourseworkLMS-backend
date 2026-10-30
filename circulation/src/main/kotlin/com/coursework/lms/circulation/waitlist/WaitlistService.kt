package com.coursework.lms.circulation.waitlist

import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.catalog.file.StoredFiles
import com.coursework.lms.circulation.CirculationErrorCode
import com.coursework.lms.circulation.db.WaitlistTable
import com.coursework.lms.circulation.db.hasActiveReservation
import com.coursework.lms.circulation.reservation.BookRef
import com.coursework.lms.core.error.ApiException
import com.coursework.lms.core.error.ErrorCode
import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import com.coursework.lms.core.paging.toPage
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime

@Service
class WaitlistService(
    private val clock: Clock,
    private val storedFiles: StoredFiles,
) {

    @Transactional
    fun join(
        bookId: Int,
        userId: Int,
    ): WaitlistEntry {
        val book = BooksTable
            .select(BooksTable.copiesAvailable)
            .where { (BooksTable.id eq bookId) and BooksTable.deletedAt.isNull() }
            .forUpdate()
            .singleOrNull()
            ?: throw ApiException(
                status = HttpStatus.NOT_FOUND,
                code = ErrorCode.NOT_FOUND,
                detail = "There is no book with id $bookId.",
            )
        if (book[BooksTable.copiesAvailable] > 0) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = CirculationErrorCode.BOOK_AVAILABLE,
                detail = "This book has free copies, so reserve it instead.",
            )
        }
        if (hasActiveReservation(userId, bookId)) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = CirculationErrorCode.ALREADY_RESERVED,
                detail = "You already have this book reserved or borrowed.",
            )
        }
        if (findEntry(bookId, userId) != null) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = CirculationErrorCode.ALREADY_WAITING,
                detail = "You are already on the waitlist for this book.",
            )
        }
        WaitlistTable.insert {
            it[WaitlistTable.bookId] = bookId
            it[WaitlistTable.userId] = userId
            it[WaitlistTable.addedAt] = OffsetDateTime.now(clock)
        }
        return checkNotNull(findEntry(bookId, userId))
    }

    /** Takes the user off the book's waitlist, declining their Offer if they have one. */
    @Transactional
    fun leave(
        bookId: Int,
        userId: Int,
    ) {
        WaitlistTable.deleteWhere { (WaitlistTable.bookId eq bookId) and (WaitlistTable.userId eq userId) }
    }

    /** The waitlists the user is on, the earliest joined first. */
    @Transactional(readOnly = true)
    fun mine(
        userId: Int,
        pageQuery: PageQuery,
    ): PageResponse<WaitlistEntry> {
        return entryQuery()
            .where { WaitlistTable.userId eq userId }
            .orderBy(WaitlistTable.addedAt to SortOrder.ASC, WaitlistTable.id to SortOrder.ASC)
            .toPage(pageQuery, ::toEntry)
    }

    /** The Offers whose 24 hours have passed, as their entry ids and their users' ids. */
    @Transactional(readOnly = true)
    fun expiredOffers(): List<Pair<Int, Int>> {
        return WaitlistTable
            .select(WaitlistTable.id, WaitlistTable.userId)
            .where { WaitlistTable.offerExpiresAt less OffsetDateTime.now(clock) }
            .map { it[WaitlistTable.id] to it[WaitlistTable.userId] }
    }

    /** Drops an expired Offer, which passes its copy to the next user in line. */
    @Transactional
    fun dropExpiredOffer(entryId: Int) {
        WaitlistTable.deleteWhere {
            (WaitlistTable.id eq entryId) and (WaitlistTable.offerExpiresAt less OffsetDateTime.now(clock))
        }
    }

    private fun findEntry(
        bookId: Int,
        userId: Int,
    ): WaitlistEntry? {
        return entryQuery()
            .where { (WaitlistTable.bookId eq bookId) and (WaitlistTable.userId eq userId) }
            .singleOrNull()
            ?.let(::toEntry)
    }

    private fun entryQuery(): Query {
        return WaitlistTable
            .join(BooksTable, JoinType.INNER, WaitlistTable.bookId, BooksTable.id)
            .select(WaitlistTable.columns + listOf(BooksTable.title, BooksTable.coverImageUrl, BooksTable.coverFileId))
    }

    private fun toEntry(row: ResultRow): WaitlistEntry {
        val offerExpiresAt = row[WaitlistTable.offerExpiresAt]
        return WaitlistEntry(
            book = BookRef(
                id = row[WaitlistTable.bookId],
                title = row[BooksTable.title],
                coverImageUrl = row[BooksTable.coverFileId]?.let(storedFiles::urlOf) ?: row[BooksTable.coverImageUrl],
            ),
            addedAt = row[WaitlistTable.addedAt],
            position = if (offerExpiresAt == null) positionOf(row) else null,
            offerExpiresAt = offerExpiresAt,
        )
    }

    private fun positionOf(row: ResultRow): Int {
        val addedAt = row[WaitlistTable.addedAt]
        val ahead = WaitlistTable
            .select(WaitlistTable.id)
            .where {
                (WaitlistTable.bookId eq row[WaitlistTable.bookId]) and
                    WaitlistTable.offeredAt.isNull() and
                    (
                        (WaitlistTable.addedAt less addedAt) or
                            ((WaitlistTable.addedAt eq addedAt) and (WaitlistTable.id less row[WaitlistTable.id]))
                        )
            }
            .count()
        return ahead.toInt() + 1
    }
}
