package com.coursework.lms.catalog.db

import com.coursework.lms.identity.db.UsersTable
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE
import org.jetbrains.exposed.v1.core.ReferenceOption.NO_ACTION
import org.jetbrains.exposed.v1.core.ReferenceOption.SET_NULL
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

private val now = CustomFunction<OffsetDateTime>("now", JavaOffsetDateTimeColumnType())

object PublishersTable : Table("publishers") {

    val id = integer("id").autoIncrement()
    val name = varchar("name", 50)

    override val primaryKey = PrimaryKey(id, name = "publishers_pk")

    init {
        uniqueIndex("publishers_name_lower_key", functions = listOf(name.lowerCase()))
    }
}

object BookLanguagesTable : Table("book_languages") {

    val id = integer("id").autoIncrement()
    val name = varchar("name", 50)

    override val primaryKey = PrimaryKey(id, name = "book_languages_pk")

    init {
        uniqueIndex("book_languages_name_lower_key", functions = listOf(name.lowerCase()))
    }
}

object AuthorsTable : Table("authors") {

    val id = integer("id").autoIncrement()
    val name = varchar("name", 50)

    override val primaryKey = PrimaryKey(id, name = "authors_pk")

    init {
        uniqueIndex("authors_name_lower_key", functions = listOf(name.lowerCase()))
    }
}

object CategoriesTable : Table("categories") {

    val id = integer("id").autoIncrement()
    val name = varchar("name", 50)

    override val primaryKey = PrimaryKey(id, name = "categories_pk")

    init {
        uniqueIndex("categories_name_lower_key", functions = listOf(name.lowerCase()))
    }
}

object FilesTable : Table("files") {

    val id = javaUUID("id")
    val contentType = varchar("content_type", 100)
    val sizeBytes = integer("size_bytes")
    val data = binary("data")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(now)

    override val primaryKey = PrimaryKey(id, name = "files_pkey")
}

object BooksTable : Table("books") {

    val id = integer("id").autoIncrement()
    val title = varchar("title", 100)
    val subtitle = varchar("subtitle", 100).nullable()
    val pdfUrl = text("pdf_url").nullable()
    val rating = float("rating").nullable()
    val description = text("description")
    val publicationYear = integer("publication_year").nullable()
    val edition = varchar("edition", 50).nullable()
    val coverImageUrl = text("cover_image_url").nullable()
    val totalCopies = integer("total_copies")
    val copiesAvailable = integer("copies_available")

    /** The book's Owner; null for a University Book. */
    val addedBy = integer("added_by")
        .references(UsersTable.id, onDelete = SET_NULL, onUpdate = CASCADE, fkName = "books_added_by_fkey")
        .nullable()
    val publisherId = integer("publisher_id")
        .references(PublishersTable.id, onDelete = NO_ACTION, onUpdate = CASCADE, fkName = "publisher_if")
    val languageId = integer("language_id")
        .references(BookLanguagesTable.id, onDelete = NO_ACTION, onUpdate = CASCADE, fkName = "language_id")
    val maxLoanDays = integer("max_reservation_days")
        .default(14)
        .check("books_max_reservation_days_check") { it greaterEq 1 }
    val coverFileId = javaUUID("cover_file_id")
        .references(FilesTable.id, onDelete = SET_NULL, onUpdate = NO_ACTION, fkName = "books_cover_file_id_fkey")
        .nullable()
    val pdfFileId = javaUUID("pdf_file_id")
        .references(FilesTable.id, onDelete = SET_NULL, onUpdate = NO_ACTION, fkName = "books_pdf_file_id_fkey")
        .nullable()

    /** Set while the book is a Hidden Book. */
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id, name = "books_pk")
}

object BookAuthorsTable : Table("book_authors") {

    val bookId = integer("book_id")
        .references(BooksTable.id, onDelete = CASCADE, onUpdate = NO_ACTION, fkName = "fk_book")
    val authorId = integer("author_id")
        .references(AuthorsTable.id, onDelete = CASCADE, onUpdate = NO_ACTION, fkName = "fk_author")

    override val primaryKey = PrimaryKey(bookId, authorId, name = "book_authors_pkey")
}

object BooksCategoriesTable : Table("books_categories") {

    val bookId = integer("book_id")
        .references(BooksTable.id, onDelete = CASCADE, onUpdate = CASCADE, fkName = "fk_book")
    val categoryId = integer("category_id")
        .references(CategoriesTable.id, onDelete = NO_ACTION, onUpdate = CASCADE, fkName = "fk_category")

    override val primaryKey = PrimaryKey(bookId, categoryId, name = "books_categories_pk")
}
