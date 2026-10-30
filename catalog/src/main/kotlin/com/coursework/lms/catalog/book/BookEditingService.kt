package com.coursework.lms.catalog.book

import com.coursework.lms.catalog.CatalogErrorCode
import com.coursework.lms.catalog.db.AuthorsTable
import com.coursework.lms.catalog.db.BookAuthorsTable
import com.coursework.lms.catalog.db.BookLanguagesTable
import com.coursework.lms.catalog.db.BooksCategoriesTable
import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.catalog.db.CategoriesTable
import com.coursework.lms.catalog.db.PublishersTable
import com.coursework.lms.catalog.file.BookFileKind
import com.coursework.lms.catalog.file.StoredFiles
import com.coursework.lms.core.error.ApiException
import com.coursework.lms.core.error.ErrorCode
import com.coursework.lms.core.security.CurrentUser
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** Adds, edits and deletes books, and attaches their cover and PDF. */
@Service
class BookEditingService(
    private val bookCatalogService: BookCatalogService,
    private val storedFiles: StoredFiles,
    private val bookLoans: BookLoans,
    private val clock: Clock,
) {

    /** Adds a book: a University Book when an admin adds it, otherwise the teacher's own with no copies yet. */
    @Transactional
    fun create(
        form: BookForm,
        user: CurrentUser,
    ): BookDetails {
        val isAdmin = user.isAdmin()
        requireAdminFieldsAllowed(form, isAdmin)
        requireReferences(form)
        val copies = if (isAdmin) form.totalCopies ?: 0 else 0
        val publisherId = publisherIdFor(form.publisher)
        val bookId = BooksTable.insert {
            writeFields(it, form, publisherId)
            it[totalCopies] = copies
            it[copiesAvailable] = copies
            it[maxLoanDays] = if (isAdmin) form.maxLoanDays ?: DEFAULT_LOAN_DAYS else DEFAULT_LOAN_DAYS
            it[addedBy] = if (isAdmin) null else user.id
        } get BooksTable.id
        replaceAuthors(bookId, form.authors)
        replaceCategories(bookId, form.categoryIds)
        return bookCatalogService.details(bookId, user.id)
    }

    @Transactional
    fun update(
        bookId: Int,
        form: BookForm,
        user: CurrentUser,
    ): BookDetails {
        val book = requireEditable(bookId, user)
        val isAdmin = user.isAdmin()
        requireAdminFieldsAllowed(form, isAdmin)
        requireReferences(form)
        form.totalCopies?.let { newTotal ->
            val held = book[BooksTable.totalCopies] - book[BooksTable.copiesAvailable]
            if (newTotal < held) {
                throw ApiException(
                    status = HttpStatus.CONFLICT,
                    code = CatalogErrorCode.COPIES_BELOW_HOLDS,
                    detail = "$held copies are reserved, borrowed or offered, so the total can't go below that.",
                )
            }
        }
        val publisherId = publisherIdFor(form.publisher)
        // Copies Available follows a new total by itself, and added copies go to the Waitlist first (V9 triggers).
        BooksTable.update({ BooksTable.id eq bookId }) {
            writeFields(it, form, publisherId)
            form.totalCopies?.let { total -> it[totalCopies] = total }
            form.maxLoanDays?.let { days -> it[maxLoanDays] = days }
        }
        replaceAuthors(bookId, form.authors)
        replaceCategories(bookId, form.categoryIds)
        return bookCatalogService.details(bookId, user.id)
    }

    /**
     * Deletes a book with everything that belongs to it. A book with Borrowed copies becomes a Hidden Book instead,
     * removed once its last copy is returned.
     */
    @Transactional
    fun delete(
        bookId: Int,
        user: CurrentUser,
    ) {
        val book = findVisible(bookId)
        if ("books.delete" !in user.permissions) {
            if ("books.delete.own" !in user.permissions || book[BooksTable.addedBy] != user.id) {
                throw accessDenied("You can only delete books you added.")
            }
            if (book[BooksTable.totalCopies] > 0) {
                throw accessDenied("A book with library copies can only be deleted by an administrator.")
            }
        }
        if (bookLoans.hasBorrowedCopies(bookId)) {
            BooksTable.update({ BooksTable.id eq bookId }) { it[deletedAt] = OffsetDateTime.now(clock) }
            bookLoans.withdrawHolds(bookId)
        } else {
            remove(book)
        }
    }

    /** Removes a Hidden Book once none of its copies is Borrowed any more. */
    @Transactional
    fun removeHiddenIfReturned(bookId: Int) {
        val book = BooksTable.selectAll()
            .where { (BooksTable.id eq bookId) and BooksTable.deletedAt.isNotNull() }
            .singleOrNull()
            ?: return
        if (!bookLoans.hasBorrowedCopies(bookId)) {
            remove(book)
        }
    }

    /** Stores [bytes] as the book's [kind] file, replacing the one it had. */
    @Transactional
    fun attachFile(
        bookId: Int,
        kind: BookFileKind,
        bytes: ByteArray,
        user: CurrentUser,
    ): BookDetails {
        val book = requireEditable(bookId, user)
        val fileId = storedFiles.store(bytes, kind)
        val (fileColumn, urlColumn) = columnsOf(kind)
        BooksTable.update({ BooksTable.id eq bookId }) {
            it[fileColumn] = fileId
            it[urlColumn] = null
        }
        book[fileColumn]?.let(storedFiles::delete)
        return bookCatalogService.details(bookId, user.id)
    }

    @Transactional
    fun detachFile(
        bookId: Int,
        kind: BookFileKind,
        user: CurrentUser,
    ): BookDetails {
        val book = requireEditable(bookId, user)
        val (fileColumn, urlColumn) = columnsOf(kind)
        BooksTable.update({ BooksTable.id eq bookId }) {
            it[fileColumn] = null
            it[urlColumn] = null
        }
        book[fileColumn]?.let(storedFiles::delete)
        return bookCatalogService.details(bookId, user.id)
    }

    private fun remove(book: ResultRow) {
        // Links, reviews, favourites, the Waitlist and finished Reservations go with the book (ON DELETE CASCADE).
        BooksTable.deleteWhere { BooksTable.id eq book[BooksTable.id] }
        listOfNotNull(book[BooksTable.coverFileId], book[BooksTable.pdfFileId]).forEach(storedFiles::delete)
    }

    private fun requireEditable(
        bookId: Int,
        user: CurrentUser,
    ): ResultRow {
        val book = findVisible(bookId)
        val owns = "books.edit.own" in user.permissions && book[BooksTable.addedBy] == user.id
        if (!user.isAdmin() && !owns) {
            throw accessDenied("You can only edit books you added.")
        }
        return book
    }

    private fun findVisible(bookId: Int): ResultRow {
        return BooksTable.selectAll()
            .where { (BooksTable.id eq bookId) and BooksTable.deletedAt.isNull() }
            .forUpdate()
            .singleOrNull()
            ?: throw ApiException(
                status = HttpStatus.NOT_FOUND,
                code = ErrorCode.NOT_FOUND,
                detail = "There is no book with id $bookId.",
            )
    }

    private fun requireAdminFieldsAllowed(
        form: BookForm,
        isAdmin: Boolean,
    ) {
        if (!isAdmin && (form.totalCopies != null || form.maxLoanDays != null)) {
            throw accessDenied("Only administrators set the number of copies and the loan length.")
        }
    }

    private fun requireReferences(form: BookForm) {
        val languageExists = BookLanguagesTable.select(BookLanguagesTable.id)
            .where { BookLanguagesTable.id eq form.languageId }
            .any()
        if (!languageExists) {
            throw badRequest("There is no language with id ${form.languageId}.")
        }
        val knownCategories = CategoriesTable.select(CategoriesTable.id)
            .where { CategoriesTable.id inList form.categoryIds }
            .map { it[CategoriesTable.id] }
            .toSet()
        val unknown = form.categoryIds.filterNot { it in knownCategories }
        if (unknown.isNotEmpty()) {
            throw badRequest("There is no category with id ${unknown.first()}.")
        }
        (form.authors + form.publisher).firstOrNull { it.length > NAME_LENGTH }?.let {
            throw badRequest("'$it' is longer than $NAME_LENGTH characters.")
        }
    }

    private fun writeFields(
        statement: UpdateBuilder<*>,
        form: BookForm,
        publisherId: Int,
    ) {
        statement[BooksTable.title] = form.title
        statement[BooksTable.subtitle] = form.subtitle
        statement[BooksTable.description] = form.description
        statement[BooksTable.publisherId] = publisherId
        statement[BooksTable.languageId] = form.languageId
        statement[BooksTable.publicationYear] = form.publicationYear
        statement[BooksTable.edition] = form.edition
    }

    private fun replaceAuthors(
        bookId: Int,
        names: List<String>,
    ) {
        val authorIds = names.map(::authorIdFor).distinct()
        BookAuthorsTable.deleteWhere { BookAuthorsTable.bookId eq bookId }
        BookAuthorsTable.batchInsert(authorIds) { authorId ->
            this[BookAuthorsTable.bookId] = bookId
            this[BookAuthorsTable.authorId] = authorId
        }
    }

    private fun replaceCategories(
        bookId: Int,
        categoryIds: List<Int>,
    ) {
        BooksCategoriesTable.deleteWhere { BooksCategoriesTable.bookId eq bookId }
        BooksCategoriesTable.batchInsert(categoryIds.distinct()) { categoryId ->
            this[BooksCategoriesTable.bookId] = bookId
            this[BooksCategoriesTable.categoryId] = categoryId
        }
    }

    // Names match ignoring case, so "robert c. martin" finds "Robert C. Martin" instead of adding a second one.
    private fun authorIdFor(name: String): Int {
        val existing = {
            AuthorsTable.select(AuthorsTable.id)
                .where { AuthorsTable.name.lowerCase() eq stringParam(name).lowerCase() }
        }
        existing().singleOrNull()?.let { return it[AuthorsTable.id] }
        AuthorsTable.insertIgnore { it[AuthorsTable.name] = name }
        return existing().single()[AuthorsTable.id]
    }

    private fun publisherIdFor(name: String): Int {
        val existing = {
            PublishersTable.select(PublishersTable.id)
                .where { PublishersTable.name.lowerCase() eq stringParam(name).lowerCase() }
        }
        existing().singleOrNull()?.let { return it[PublishersTable.id] }
        PublishersTable.insertIgnore { it[PublishersTable.name] = name }
        return existing().single()[PublishersTable.id]
    }

    private fun columnsOf(kind: BookFileKind): Pair<Column<UUID?>, Column<String?>> {
        return when (kind) {
            BookFileKind.COVER -> BooksTable.coverFileId to BooksTable.coverImageUrl
            BookFileKind.PDF -> BooksTable.pdfFileId to BooksTable.pdfUrl
        }
    }

    private fun CurrentUser.isAdmin(): Boolean {
        return "books.edit" in permissions
    }

    private fun accessDenied(detail: String): ApiException {
        return ApiException(
            status = HttpStatus.FORBIDDEN,
            code = ErrorCode.ACCESS_DENIED,
            detail = detail,
        )
    }

    private fun badRequest(detail: String): ApiException {
        return ApiException(
            status = HttpStatus.BAD_REQUEST,
            code = ErrorCode.BAD_REQUEST,
            detail = detail,
        )
    }

    private companion object {
        const val DEFAULT_LOAN_DAYS = 14
        const val NAME_LENGTH = 50
    }
}
