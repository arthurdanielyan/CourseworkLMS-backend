package com.coursework.lms.core.email

import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

/** An [EmailSender] that hands messages to the SMTP server configured under `spring.mail.*`. */
@Component
class SmtpEmailSender(
    private val mailSender: JavaMailSender,
    @Value("\${lms.mail.from}") private val from: String,
) : EmailSender {

    override fun send(
        to: String,
        subject: String,
        body: String,
    ) {
        val message = SimpleMailMessage().apply {
            setFrom(from)
            setTo(to)
            setSubject(subject)
            text = body
        }
        mailSender.send(message)
    }
}
