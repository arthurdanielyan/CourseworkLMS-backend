package com.coursework.lms.circulation.db

import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.circulation.reservation.ReservationStatus
import com.coursework.lms.identity.db.UsersTable
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE
import org.jetbrains.exposed.v1.core.ReferenceOption.NO_ACTION
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

private val now = CustomFunction<OffsetDateTime>("now", JavaOffsetDateTimeColumnType())

object ReservationsTable : Table("reservations") {

    val id = integer("id").autoIncrement()
    val userId = integer("user_id")
        .references(UsersTable.id, onDelete = NO_ACTION, onUpdate = CASCADE, fkName = "user_id")
    val bookId = integer("book_id")
        .references(BooksTable.id, onDelete = CASCADE, onUpdate = CASCADE, fkName = "book_id")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(now)
    val returnDeadline = date("return_deadline")
    val status = varchar("status", 20)
        .default(ReservationStatus.RESERVED.value)
        .check("reservations_status_check") { it inList ReservationStatus.entries.map(ReservationStatus::value) }
    val borrowedAt = timestampWithTimeZone("borrowed_at").nullable()
    val finishedAt = timestampWithTimeZone("finished_at").nullable()

    override val primaryKey = PrimaryKey(id, name = "reservations_pk")

    init {
        uniqueIndex("reservations_one_active_per_user_book", userId, bookId) {
            status inList ReservationStatus.active.map(ReservationStatus::value)
        }
    }
}

object WaitlistTable : Table("waitlist") {

    val id = integer("id").autoIncrement()
    val bookId = integer("book_id")
        .references(BooksTable.id, onDelete = CASCADE, onUpdate = NO_ACTION, fkName = "waitlist_book_id_fkey")
    val userId = integer("user_id")
        .references(UsersTable.id, onDelete = CASCADE, onUpdate = NO_ACTION, fkName = "waitlist_user_id_fkey")
    val addedAt = timestampWithTimeZone("added_at").defaultExpression(now)
    val offeredAt = timestampWithTimeZone("offered_at").nullable()
    val offerExpiresAt = timestampWithTimeZone("offer_expires_at").nullable()

    override val primaryKey = PrimaryKey(id, name = "waitlist_pkey")

    init {
        uniqueIndex("unique_waitlist_entry", bookId, userId)
        index("waitlist_book_id_added_at_idx", false, bookId, addedAt, id)
        check("waitlist_offer_check") {
            (offeredAt.isNull() and offerExpiresAt.isNull()) or (offeredAt.isNotNull() and offerExpiresAt.isNotNull())
        }
    }
}
