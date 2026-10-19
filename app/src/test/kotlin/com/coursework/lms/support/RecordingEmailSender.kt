package com.coursework.lms.support

import com.coursework.lms.core.email.EmailSender
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

/** An [EmailSender] that keeps every email instead of sending it, for tests to read. */
@Primary
@Component
class RecordingEmailSender : EmailSender {

    /** One recorded email. */
    data class Email(
        val to: String,
        val subject: String,
        val body: String,
    )

    private val sent = CopyOnWriteArrayList<Email>()

    override fun send(
        to: String,
        subject: String,
        body: String,
    ) {
        sent += Email(
            to = to,
            subject = subject,
            body = body,
        )
    }

    fun sentTo(address: String): List<Email> {
        return sent.filter { it.to == address }
    }

    /** The six-digit code in the latest email to [address]. */
    fun lastCodeFor(address: String): String {
        val body = checkNotNull(sentTo(address).lastOrNull()) { "no email was sent to $address" }.body
        return checkNotNull(Regex("\\b\\d{6}\\b").find(body)) { "no code in the email to $address" }.value
    }
}
