package com.coursework.lms.identity.registration

import com.coursework.lms.core.email.EmailSender
import org.springframework.stereotype.Component
import java.time.Duration

/** Sends a new user the Verification Code that completes their signup. */
@Component
class VerificationEmail(
    private val emailSender: EmailSender,
) {

    fun send(
        to: String,
        code: String,
        validFor: Duration,
    ) {
        emailSender.send(
            to = to,
            subject = "Your LMS verification code",
            body = """
                Your verification code is $code.
                It expires in ${validFor.toMinutes()} minutes.

                If you did not sign up for the Polytechnic library, you can ignore this email.
            """.trimIndent(),
        )
    }
}
