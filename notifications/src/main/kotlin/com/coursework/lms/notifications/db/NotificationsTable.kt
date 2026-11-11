package com.coursework.lms.notifications.db

import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.identity.db.UsersTable
import com.coursework.lms.notifications.NotificationKind
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE
import org.jetbrains.exposed.v1.core.ReferenceOption.NO_ACTION
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

private val now = CustomFunction<OffsetDateTime>("now", JavaOffsetDateTimeColumnType())

object NotificationsTable : Table("notifications") {

    val id = integer("id").autoIncrement()
    val userId = integer("user_id")
        .references(UsersTable.id, onDelete = CASCADE, onUpdate = NO_ACTION, fkName = "notifications_user_id_fkey")
    val title = text("title")
    val message = text("message")
    val type = varchar("type", 30)
        .check("notifications_type_check") { it inList NotificationKind.entries.map(NotificationKind::value) }
    val isRead = bool("is_read").default(false)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(now)
    val bookId = integer("book_id")
        .references(BooksTable.id, onDelete = CASCADE, onUpdate = NO_ACTION, fkName = "notifications_book_id_fkey")
        .nullable()
    val dedupeKey = varchar("dedupe_key", 100)
    val emailedAt = timestampWithTimeZone("emailed_at").nullable()

    override val primaryKey = PrimaryKey(id, name = "notifications_pkey")

    init {
        uniqueIndex("notifications_user_id_dedupe_key_key", userId, dedupeKey)
        index("notifications_user_id_created_at_idx", false, userId, createdAt)
        index("notifications_unsent_idx", false, id) { emailedAt.isNull() }
    }
}
