package com.coursework.lms.notifications

import com.coursework.lms.circulation.reservation.BookRef
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.time.OffsetDateTime

/** What a notification is about, stored and sent as [value]. */
enum class NotificationKind(
    val value: String,
) {

    WAITLIST_OFFER("waitlist_offer"),
    PICKUP_REMINDER("pickup_reminder"),
    RESERVATION_EXPIRED("reservation_expired"),
    RETURN_REMINDER("return_reminder"),
    OVERDUE("overdue"),
    ;

    companion object {

        fun fromValue(value: String): NotificationKind {
            return entries.first { it.value == value }
        }
    }
}

data class Notification(
    val id: Int,
    val kind: NotificationKind,
    val title: String,
    val message: String,
    val book: BookRef?,
    val read: Boolean,
    val createdAt: OffsetDateTime,
)

/**
 * Bound from `lms.notifications.*`. [pickupReminderBefore] is how long before the Pickup Window closes the reminder
 * goes out; [reminderHour] is the hour of the library's day from which return and overdue reminders go out.
 */
@ConfigurationProperties("lms.notifications")
data class NotificationProperties(
    val pickupReminderBefore: Duration = Duration.ofHours(12),
    val reminderHour: Int = 9,
    val retention: Duration = Duration.ofDays(90),
)

@Configuration
@EnableConfigurationProperties(NotificationProperties::class)
class NotificationsConfig
