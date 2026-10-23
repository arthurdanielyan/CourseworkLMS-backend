package com.coursework.lms.catalog.book

import com.coursework.lms.catalog.NamedItem
import com.coursework.lms.catalog.db.AuthorsTable
import com.coursework.lms.catalog.db.BookAuthorsTable
import com.coursework.lms.catalog.db.BookLanguagesTable
import com.coursework.lms.catalog.db.BooksCategoriesTable
import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.catalog.db.CategoriesTable
import com.coursework.lms.catalog.db.PublishersTable
import com.coursework.lms.core.error.ApiException
import com.coursework.lms.core.error.ErrorCode
import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import com.coursework.lms.core.paging.toPage
import com.coursework.lms.identity.db.UsersTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.compoundAnd
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BookCatalogService {

    @Transactional(readOnly = true)
    fun search(
        search: BookSearch,
        pageQuery: PageQuery,
    ): PageResponse<BookSummary> {
        val rows = BooksTable
            .join(PublishersTable, JoinType.INNER, BooksTable.publisherId, PublishersTable.id)
            .select(
                BooksTable.id,
                BooksTable.title,
                BooksTable.subtitle,
                BooksTable.coverImageUrl,
                BooksTable.pdfUrl,
                BooksTable.rating,
                BooksTable.copiesAvailable,
                PublishersTable.name,
            )
            .where { matching(search) }
            .orderBy(BooksTable.title to SortOrder.ASC, BooksTable.id to SortOrder.ASC)
            .toPage(pageQuery) { it }
        val authors = authorsOf(rows.content.map { it[BooksTable.id] })
        return rows.map { row ->
            BookSummary(
                id = row[BooksTable.id],
                title = row[BooksTable.title],
                subtitle = row[BooksTable.subtitle],
                authors = authors[row[BooksTable.id]].orEmpty().map { it.name },
                publisher = row[PublishersTable.name],
                coverImageUrl = row[BooksTable.coverImageUrl],
                hasPdf = row[BooksTable.pdfUrl] != null,
                rating = row[BooksTable.rating],
                available = row[BooksTable.copiesAvailable] > 0,
            )
        }
    }

    @Transactional(readOnly = true)
    fun details(bookId: Int): BookDetails {
        val row = BooksTable
            .join(PublishersTable, JoinType.INNER, BooksTable.publisherId, PublishersTable.id)
            .join(BookLanguagesTable, JoinType.INNER, BooksTable.languageId, BookLanguagesTable.id)
            .join(UsersTable, JoinType.LEFT, BooksTable.addedBy, UsersTable.id)
            .select(
                BooksTable.columns + listOf(
                    PublishersTable.name,
                    BookLanguagesTable.name,
                    UsersTable.id,
                    UsersTable.username,
                ),
            )
            .where { BooksTable.id eq bookId }
            .singleOrNull()
            ?: throw ApiException(
                status = HttpStatus.NOT_FOUND,
                code = ErrorCode.NOT_FOUND,
                detail = "There is no book with id $bookId.",
            )
        val ownerId = row.getOrNull(UsersTable.id)
        return BookDetails(
            id = row[BooksTable.id],
            title = row[BooksTable.title],
            subtitle = row[BooksTable.subtitle],
            description = row[BooksTable.description],
            authors = authorsOf(listOf(bookId))[bookId].orEmpty(),
            publisher = NamedItem(row[BooksTable.publisherId], row[PublishersTable.name]),
            language = NamedItem(row[BooksTable.languageId], row[BookLanguagesTable.name]),
            categories = categoriesOf(bookId),
            publicationYear = row[BooksTable.publicationYear],
            edition = row[BooksTable.edition],
            coverImageUrl = row[BooksTable.coverImageUrl],
            pdfUrl = row[BooksTable.pdfUrl],
            totalCopies = row[BooksTable.totalCopies],
            copiesAvailable = row[BooksTable.copiesAvailable],
            rating = row[BooksTable.rating],
            owner = ownerId?.let { NamedItem(it, row[UsersTable.username]) },
        )
    }

    private fun matching(search: BookSearch): Op<Boolean> {
        val criteria = buildList {
            search.text?.let { text ->
                val pattern = containing(text)
                add(
                    (BooksTable.title.lowerCase() like pattern) or
                        (BooksTable.subtitle.lowerCase() like pattern) or
                        hasAuthorMatching(pattern),
                )
            }
            search.author?.let { add(hasAuthorMatching(containing(it))) }
            search.publicationYear?.let { add(BooksTable.publicationYear eq it) }
            if (search.categoryIds.isNotEmpty()) {
                add(inAnyCategory(search.categoryIds))
            }
            if (search.languageIds.isNotEmpty()) {
                add(BooksTable.languageId inList search.languageIds)
            }
            search.available?.let { available ->
                add(if (available) BooksTable.copiesAvailable greater 0 else BooksTable.copiesAvailable eq 0)
            }
            ownedBy(search)?.let { add(it) }
        }
        return if (criteria.isEmpty()) Op.TRUE else criteria.compoundAnd()
    }

    private fun ownedBy(search: BookSearch): Op<Boolean>? {
        val owners = search.ownerIds.takeIf { it.isNotEmpty() }?.let { BooksTable.addedBy inList it }
        val university = if (search.universityBooks) BooksTable.addedBy.isNull() else null
        return when {
            owners != null && university != null -> owners or university
            else -> owners ?: university
        }
    }

    private fun hasAuthorMatching(pattern: LikePattern): Op<Boolean> {
        return exists(
            BookAuthorsTable
                .join(AuthorsTable, JoinType.INNER, BookAuthorsTable.authorId, AuthorsTable.id)
                .select(BookAuthorsTable.bookId)
                .where { (BookAuthorsTable.bookId eq BooksTable.id) and (AuthorsTable.name.lowerCase() like pattern) },
        )
    }

    private fun inAnyCategory(categoryIds: List<Int>): Op<Boolean> {
        return exists(
            BooksCategoriesTable
                .select(BooksCategoriesTable.bookId)
                .where {
                    (BooksCategoriesTable.bookId eq BooksTable.id) and
                        (BooksCategoriesTable.categoryId inList categoryIds)
                },
        )
    }

    // Escaped, so a % or _ the user types isn't a wildcard.
    private fun containing(text: String): LikePattern {
        val literal = LikePattern.ofLiteral(text.lowercase())
        return LikePattern("%${literal.pattern}%", literal.escapeChar)
    }

    private fun authorsOf(bookIds: List<Int>): Map<Int, List<NamedItem>> {
        if (bookIds.isEmpty()) {
            return emptyMap()
        }
        return BookAuthorsTable
            .join(AuthorsTable, JoinType.INNER, BookAuthorsTable.authorId, AuthorsTable.id)
            .select(BookAuthorsTable.bookId, AuthorsTable.id, AuthorsTable.name)
            .where { BookAuthorsTable.bookId inList bookIds }
            .orderBy(AuthorsTable.name to SortOrder.ASC)
            .groupBy(
                keySelector = { it[BookAuthorsTable.bookId] },
                valueTransform = { NamedItem(it[AuthorsTable.id], it[AuthorsTable.name]) },
            )
    }

    private fun categoriesOf(bookId: Int): List<NamedItem> {
        return BooksCategoriesTable
            .join(CategoriesTable, JoinType.INNER, BooksCategoriesTable.categoryId, CategoriesTable.id)
            .select(CategoriesTable.id, CategoriesTable.name)
            .where { BooksCategoriesTable.bookId eq bookId }
            .orderBy(CategoriesTable.name to SortOrder.ASC)
            .map { NamedItem(it[CategoriesTable.id], it[CategoriesTable.name]) }
    }
}
