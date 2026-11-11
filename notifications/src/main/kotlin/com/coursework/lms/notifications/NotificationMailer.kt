package com.coursework.lms.notifications

import com.coursework.lms.core.email.EmailSender
import com.coursework.lms.identity.db.UsersTable
import com.coursework.lms.notifications.db.NotificationsTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.OffsetDateTime

/** Emails the notifications not emailed yet, each marked once its email is out. */
@Component
class NotificationMailer(
    private val emailSender: EmailSender,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(NotificationMailer::class.java)

    fun sendPending() {
        val pending = transactionTemplate.execute {
            NotificationsTable
                .join(UsersTable, JoinType.INNER, NotificationsTable.userId, UsersTable.id)
                .select(NotificationsTable.id, NotificationsTable.title, NotificationsTable.message, UsersTable.email)
                .where { NotificationsTable.emailedAt.isNull() }
                .orderBy(NotificationsTable.id to SortOrder.ASC)
                .limit(BATCH)
                .toList()
        }.orEmpty()
        // One transaction per email: a mail server failure leaves just that one unmarked, to be tried next time.
        pending.forEach { row ->
            try {
                emailSender.send(
                    to = row[UsersTable.email],
                    subject = row[NotificationsTable.title],
                    body = "${row[NotificationsTable.message]}\n\nYou can see all your notifications in the LMS app.",
                )
                transactionTemplate.executeWithoutResult {
                    NotificationsTable.update({ NotificationsTable.id eq row[NotificationsTable.id] }) {
                        it[emailedAt] = OffsetDateTime.now(clock)
                    }
                }
            } catch (error: RuntimeException) {
                log.warn("Could not email notification {}", row[NotificationsTable.id], error)
            }
        }
    }

    private companion object {
        const val BATCH = 50
    }
}
