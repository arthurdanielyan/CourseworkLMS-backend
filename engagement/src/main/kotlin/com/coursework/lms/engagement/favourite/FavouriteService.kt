package com.coursework.lms.engagement.favourite

import com.coursework.lms.catalog.book.BookCatalogService
import com.coursework.lms.catalog.book.BookSummary
import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.core.error.ApiException
import com.coursework.lms.core.error.ErrorCode
import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import com.coursework.lms.core.paging.toPage
import com.coursework.lms.engagement.db.UsersFavouritesTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FavouriteService(
    private val bookCatalogService: BookCatalogService,
) {

    /** The user's Favourites, the most recently added first. */
    @Transactional(readOnly = true)
    fun list(
        userId: Int,
        pageQuery: PageQuery,
    ): PageResponse<BookSummary> {
        val bookIds = UsersFavouritesTable
            .select(UsersFavouritesTable.bookId)
            .where { UsersFavouritesTable.userId eq userId }
            .orderBy(UsersFavouritesTable.addedAt to SortOrder.DESC, UsersFavouritesTable.bookId to SortOrder.DESC)
            .toPage(pageQuery) { it[UsersFavouritesTable.bookId] }
        return PageResponse(
            content = bookCatalogService.summaries(bookIds.content, userId),
            page = bookIds.page,
        )
    }

    @Transactional
    fun add(
        userId: Int,
        bookId: Int,
    ) {
        val bookExists = BooksTable.select(BooksTable.id).where { BooksTable.id eq bookId }.any()
        if (!bookExists) {
            throw ApiException(
                status = HttpStatus.NOT_FOUND,
                code = ErrorCode.NOT_FOUND,
                detail = "There is no book with id $bookId.",
            )
        }
        UsersFavouritesTable.insertIgnore {
            it[UsersFavouritesTable.userId] = userId
            it[UsersFavouritesTable.bookId] = bookId
        }
    }

    @Transactional
    fun remove(
        userId: Int,
        bookId: Int,
    ) {
        UsersFavouritesTable.deleteWhere {
            (UsersFavouritesTable.userId eq userId) and (UsersFavouritesTable.bookId eq bookId)
        }
    }
}
