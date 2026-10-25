package com.coursework.lms.engagement.db

import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.identity.db.UsersTable
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
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
