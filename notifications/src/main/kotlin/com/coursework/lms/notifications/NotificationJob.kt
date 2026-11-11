package com.coursework.lms.notifications

import com.coursework.lms.notifications.db.NotificationsTable
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.OffsetDateTime

/** Creates the notifications that are due, emails them, and drops the ones past their retention. */
@Component
class NotificationJob(
    private val planner: NotificationPlanner,
    private val mailer: NotificationMailer,
    private val properties: NotificationProperties,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {

    @Scheduled(
        initialDelayString = "\${lms.notifications.check-interval:PT1M}",
        fixedDelayString = "\${lms.notifications.check-interval:PT1M}",
    )
    fun run() {
        planner.planDue()
        mailer.sendPending()
        transactionTemplate.executeWithoutResult {
            NotificationsTable.deleteWhere { createdAt less OffsetDateTime.now(clock).minus(properties.retention) }
        }
    }
}
