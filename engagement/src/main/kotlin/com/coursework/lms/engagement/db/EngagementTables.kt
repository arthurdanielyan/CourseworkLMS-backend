package com.coursework.lms.engagement.db

import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.identity.db.UsersTable
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE
import org.jetbrains.exposed.v1.core.ReferenceOption.NO_ACTION
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.javatime.CurrentDate
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

private val now = CustomFunction<OffsetDateTime>("now", JavaOffsetDateTimeColumnType())

object UsersFavouritesTable : Table("users_favourites") {

    val userId = integer("user_id")
        .references(UsersTable.id, onDelete = CASCADE, onUpdate = CASCADE, fkName = "users_favourites_user_id_fkey")
    val bookId = integer("book_id")
        .references(BooksTable.id, onDelete = CASCADE, onUpdate = CASCADE, fkName = "users_favourites_book_id_fkey")
    val addedAt = timestampWithTimeZone("added_at").defaultExpression(now)

    override val primaryKey = PrimaryKey(userId, bookId, name = "users_favourites_pk")
}

object BookReviewsTable : Table("book_reviews") {

    val id = integer("id").autoIncrement()
    val userId = integer("user_id")
        .references(UsersTable.id, onDelete = NO_ACTION, onUpdate = CASCADE, fkName = "user_id")
    val bookId = integer("book_id")
        .references(BooksTable.id, onDelete = CASCADE, onUpdate = CASCADE, fkName = "book_reviews_book_id_fkey")
    val stars = short("stars").check("book_reviews_stars_check") { it.between(1.toShort(), 5.toShort()) }
    val text = text("review").nullable()
    val createdAt = date("created_at").defaultExpression(CurrentDate)

    override val primaryKey = PrimaryKey(id, name = "book_reviews_pk")

    init {
        uniqueIndex("book_reviews_user_id_book_id_key", userId, bookId)
        index("book_reviews_book_id_idx", false, bookId)
    }
}
