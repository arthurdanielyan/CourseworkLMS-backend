package com.coursework.lms.notifications

import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.catalog.file.StoredFiles
import com.coursework.lms.circulation.reservation.BookRef
import com.coursework.lms.core.error.ApiException
import com.coursework.lms.core.error.ErrorCode
import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import com.coursework.lms.core.paging.toPage
import com.coursework.lms.notifications.db.NotificationsTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A user's notification inbox. */
@Service
class NotificationService(
    private val storedFiles: StoredFiles,
) {

    /** The user's notifications, the newest first. */
    @Transactional(readOnly = true)
    fun list(
        userId: Int,
        unreadOnly: Boolean,
        pageQuery: PageQuery,
    ): PageResponse<Notification> {
        val mine = NotificationsTable.userId eq userId
        return NotificationsTable
            .join(BooksTable, JoinType.LEFT, NotificationsTable.bookId, BooksTable.id)
            .select(NotificationsTable.columns + listOf(BooksTable.title, BooksTable.coverImageUrl, BooksTable.coverFileId))
            .where { if (unreadOnly) mine and (NotificationsTable.isRead eq false) else mine }
            .orderBy(NotificationsTable.createdAt to SortOrder.DESC, NotificationsTable.id to SortOrder.DESC)
            .toPage(pageQuery, ::toNotification)
    }

    @Transactional(readOnly = true)
    fun unreadCount(userId: Int): Long {
        return NotificationsTable.selectAll()
            .where { (NotificationsTable.userId eq userId) and (NotificationsTable.isRead eq false) }
            .count()
    }

    @Transactional
    fun markRead(
        notificationId: Int,
        userId: Int,
    ) {
        val updated = NotificationsTable.update({
            (NotificationsTable.id eq notificationId) and (NotificationsTable.userId eq userId)
        }) {
            it[isRead] = true
        }
        if (updated == 0) {
            throw ApiException(
                status = HttpStatus.NOT_FOUND,
                code = ErrorCode.NOT_FOUND,
                detail = "There is no notification with id $notificationId.",
            )
        }
    }

    @Transactional
    fun markAllRead(userId: Int) {
        NotificationsTable.update({ (NotificationsTable.userId eq userId) and (NotificationsTable.isRead eq false) }) {
            it[isRead] = true
        }
    }

    private fun toNotification(row: ResultRow): Notification {
        val bookId = row[NotificationsTable.bookId]
        return Notification(
            id = row[NotificationsTable.id],
            kind = NotificationKind.fromValue(row[NotificationsTable.type]),
            title = row[NotificationsTable.title],
            message = row[NotificationsTable.message],
            book = bookId?.let {
                BookRef(
                    id = it,
                    title = row[BooksTable.title],
                    coverImageUrl = row[BooksTable.coverFileId]?.let(storedFiles::urlOf) ?: row[BooksTable.coverImageUrl],
                )
            },
            read = row[NotificationsTable.isRead],
            createdAt = row[NotificationsTable.createdAt],
        )
    }
}
