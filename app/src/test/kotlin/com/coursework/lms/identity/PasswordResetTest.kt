package com.coursework.lms.identity

import com.coursework.lms.core.testing.assertSchemaMatches
import com.coursework.lms.identity.db.PasswordResetsTable
import com.coursework.lms.support.AuthFlows
import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.MutableClock
import com.coursework.lms.support.RecordingEmailSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import java.time.Duration

class PasswordResetTest : IntegrationTest() {

    @Autowired
    private lateinit var auth: AuthFlows

    @Autowired
    private lateinit var emails: RecordingEmailSender

    @Autowired
    private lateinit var clock: MutableClock

    @BeforeEach
    @AfterEach
    fun resetClock() {
        clock.reset()
    }

    @Test
    fun `the password resets table matches the migrated schema`() {
        assertSchemaMatches(PasswordResetsTable)
    }

    @Test
    fun `a user sets a new password with the emailed code and logs in with it`() {
        val email = auth.newEmail()
        val oldSession = auth.register(email)

        auth.startPasswordReset(email).andExpect {
            status { isAccepted() }
            jsonPath("$.email") { value(email) }
            jsonPath("$.code_expires_at") { exists() }
        }
        val code = emails.lastCodeFor(email)
        auth.verifyPasswordReset(email, code).andExpect { status { isNoContent() } }
        auth.completePasswordReset(email, code, NEW_PASSWORD).andExpect { status { isNoContent() } }

        auth.login(email, AuthFlows.PASSWORD).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_credentials") }
        }
        auth.login(email, NEW_PASSWORD).andExpect { status { isOk() } }
        auth.refresh(oldSession.refreshToken).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_refresh_token") }
        }
        auth.completePasswordReset(email, code, "Another#2028").andExpect {
            jsonPath("$.code") { value("invalid_code") }
        }
    }

    @Test
    fun `an email without an account gets the same answer and no email`() {
        val email = auth.newEmail()

        auth.startPasswordReset(email).andExpect {
            status { isAccepted() }
            jsonPath("$.email") { value(email) }
        }
        assertThat(emails.sentTo(email)).isEmpty()
        auth.verifyPasswordReset(email, "123456").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_code") }
        }
    }

    @Test
    fun `a new code waits out the resend cooldown`() {
        val email = auth.newEmail()
        auth.register(email)
        auth.startPasswordReset(email)

        auth.startPasswordReset(email).andExpect {
            status { isTooManyRequests() }
            header { exists(HttpHeaders.RETRY_AFTER) }
        }
        val firstCode = emails.lastCodeFor(email)
        clock.advance(Duration.ofMinutes(3))
        auth.startPasswordReset(email).andExpect { status { isAccepted() } }

        val secondCode = emails.lastCodeFor(email)
        if (firstCode != secondCode) {
            auth.verifyPasswordReset(email, firstCode).andExpect { jsonPath("$.code") { value("invalid_code") } }
        }
        auth.verifyPasswordReset(email, secondCode).andExpect { status { isNoContent() } }
    }

    @Test
    fun `wrong codes run out, and an expired code stops working`() {
        val email = auth.newEmail()
        auth.register(email)
        auth.startPasswordReset(email)
        val code = emails.lastCodeFor(email)
        val wrong = if (code == "000000") "111111" else "000000"

        repeat(4) {
            auth.verifyPasswordReset(email, wrong).andExpect { jsonPath("$.code") { value("invalid_code") } }
        }
        auth.verifyPasswordReset(email, wrong).andExpect { jsonPath("$.code") { value("too_many_attempts") } }
        auth.verifyPasswordReset(email, code).andExpect { jsonPath("$.code") { value("too_many_attempts") } }

        clock.advance(Duration.ofMinutes(3))
        auth.startPasswordReset(email)
        clock.advance(Duration.ofMinutes(11))
        auth.verifyPasswordReset(email, emails.lastCodeFor(email)).andExpect {
            jsonPath("$.code") { value("code_expired") }
        }
    }

    @Test
    fun `the new password must follow the password rules`() {
        val email = auth.newEmail()
        auth.register(email)
        auth.startPasswordReset(email)

        auth.completePasswordReset(email, emails.lastCodeFor(email), "short").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("validation_failed") }
            jsonPath("$.errors[0].field") { value("new_password") }
        }
    }

    private companion object {
        const val NEW_PASSWORD = "Library#2027"
    }
}
