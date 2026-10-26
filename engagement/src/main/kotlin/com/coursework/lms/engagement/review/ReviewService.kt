package com.coursework.lms.engagement.review

import com.coursework.lms.catalog.NamedItem
import com.coursework.lms.catalog.book.BookCatalogService
import com.coursework.lms.core.error.ApiException
import com.coursework.lms.core.error.ErrorCode
import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import com.coursework.lms.core.paging.toPage
import com.coursework.lms.engagement.db.BookReviewsTable
import com.coursework.lms.identity.db.UsersTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReviewService(
    private val bookCatalogService: BookCatalogService,
) {

    /** A book's Reviews, newest first, optionally only those with one of [stars]. */
    @Transactional(readOnly = true)
    fun list(
        bookId: Int,
        stars: List<Int>,
        userId: Int,
        pageQuery: PageQuery,
    ): PageResponse<Review> {
        bookCatalogService.requireBook(bookId)
        val ofBook = BookReviewsTable.bookId eq bookId
        val condition = if (stars.isEmpty()) {
            ofBook
        } else {
            ofBook and (BookReviewsTable.stars inList stars.map { it.toShort() })
        }
        return reviewQuery()
            .where { condition }
            .orderBy(BookReviewsTable.createdAt to SortOrder.DESC, BookReviewsTable.id to SortOrder.DESC)
            .toPage(pageQuery) { toReview(it, userId) }
    }

    @Transactional(readOnly = true)
    fun summary(
        bookId: Int,
        userId: Int,
    ): ReviewSummary {
        bookCatalogService.requireBook(bookId)
        val reviewCount = BookReviewsTable.id.count()
        val counts = BookReviewsTable
            .select(BookReviewsTable.stars, reviewCount)
            .where { BookReviewsTable.bookId eq bookId }
            .groupBy(BookReviewsTable.stars)
            .associate { it[BookReviewsTable.stars].toInt() to it[reviewCount].toInt() }
        return ReviewSummary(
            starCounts = (1..5).associateWith { counts[it] ?: 0 },
            myReview = findMine(bookId, userId),
        )
    }

    /** Writes the user's Review of the book, replacing the one they wrote before. */
    @Transactional
    fun writeMine(
        bookId: Int,
        userId: Int,
        stars: Int,
        text: String?,
    ): Review {
        bookCatalogService.requireBook(bookId)
        BookReviewsTable.upsert(BookReviewsTable.userId, BookReviewsTable.bookId) {
            it[BookReviewsTable.userId] = userId
            it[BookReviewsTable.bookId] = bookId
            it[BookReviewsTable.stars] = stars.toShort()
            it[BookReviewsTable.text] = text
        }
        return checkNotNull(findMine(bookId, userId))
    }

    @Transactional
    fun deleteMine(
        bookId: Int,
        userId: Int,
    ) {
        BookReviewsTable.deleteWhere {
            (BookReviewsTable.bookId eq bookId) and (BookReviewsTable.userId eq userId)
        }
    }

    /** Deletes anyone's Review, as moderation. */
    @Transactional
    fun delete(reviewId: Int) {
        val deleted = BookReviewsTable.deleteWhere { BookReviewsTable.id eq reviewId }
        if (deleted == 0) {
            throw ApiException(
                status = HttpStatus.NOT_FOUND,
                code = ErrorCode.NOT_FOUND,
                detail = "There is no review with id $reviewId.",
            )
        }
    }

    private fun findMine(
        bookId: Int,
        userId: Int,
    ): Review? {
        return reviewQuery()
            .where { (BookReviewsTable.bookId eq bookId) and (BookReviewsTable.userId eq userId) }
            .singleOrNull()
            ?.let { toReview(it, userId) }
    }

    private fun reviewQuery(): Query {
        return BookReviewsTable
            .join(UsersTable, JoinType.INNER, BookReviewsTable.userId, UsersTable.id)
            .select(BookReviewsTable.columns + UsersTable.username)
    }

    private fun toReview(
        row: ResultRow,
        userId: Int,
    ): Review {
        return Review(
            id = row[BookReviewsTable.id],
            author = NamedItem(row[BookReviewsTable.userId], row[UsersTable.username]),
            stars = row[BookReviewsTable.stars].toInt(),
            text = row[BookReviewsTable.text],
            createdAt = row[BookReviewsTable.createdAt],
            mine = row[BookReviewsTable.userId] == userId,
        )
    }
}
