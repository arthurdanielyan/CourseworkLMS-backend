package com.coursework.lms.identity.passwordreset

import com.coursework.lms.core.email.EmailSender
import org.springframework.stereotype.Component
import java.time.Duration

/** Sends a user the code that lets them choose a new password. */
@Component
class PasswordResetEmail(
    private val emailSender: EmailSender,
) {

    fun send(
        to: String,
        code: String,
        validFor: Duration,
    ) {
        emailSender.send(
            to = to,
            subject = "Your LMS password reset code",
            body = """
                Your password reset code is $code.
                It expires in ${validFor.toMinutes()} minutes.

                If you did not ask to reset your password, you can ignore this email; your password stays the same.
            """.trimIndent(),
        )
    }
}
