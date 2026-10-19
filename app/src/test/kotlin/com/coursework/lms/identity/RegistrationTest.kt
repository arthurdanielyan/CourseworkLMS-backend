package com.coursework.lms.identity

import com.coursework.lms.core.db.DbUserContext
import com.coursework.lms.support.AuthFlows
import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.MutableClock
import com.coursework.lms.support.RecordingEmailSender
import com.coursework.lms.support.SeedUsers
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

class RegistrationTest : IntegrationTest() {

    @Autowired
    private lateinit var auth: AuthFlows

    @Autowired
    private lateinit var emails: RecordingEmailSender

    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    private lateinit var userContext: DbUserContext

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @BeforeEach
    fun resetClock() {
        clock.reset()
    }

    @Test
    fun `a verified signup becomes a logged-in student account`() {
        val email = auth.newEmail()

        auth.startRegistration(email = email, fullName = "  Anna Petrosyan ").andExpect {
            status { isAccepted() }
            jsonPath("$.email") { value(email) }
            jsonPath("$.code_expires_at") { value(notNullValue()) }
            jsonPath("$.resend_available_at") { value(notNullValue()) }
        }
        assertThat(emails.sentTo(email)).hasSize(1)

        val session = auth.session(
            auth.verify(email, emails.lastCodeFor(email)).andExpect { status { isCreated() } },
        )

        mockMvc.get("/api/v1/test-probe/me") {
            header(HttpHeaders.AUTHORIZATION, session.bearer)
        }.andExpect {
            status { isOk() }
            jsonPath("$.role") { value("student") }
        }
        val user = jdbcClient.sql("select username, password_hash from users where email = :email")
            .param("email", email)
            .query()
            .singleRow()
        assertThat(user["username"]).isEqualTo("Anna Petrosyan")
        assertThat(passwordEncoder.matches(AuthFlows.PASSWORD, user["password_hash"] as String)).isTrue()
        assertThat(pendingCount(email)).isZero()
    }

    @Test
    fun `a Teacher Email signs up as a teacher`() {
        val email = auth.newEmail()
        userContext.runAs(SeedUsers.admin.id) {
            transactionTemplate.executeWithoutResult {
                jdbcClient.sql("insert into teacher_emails (email) values (:email)").param("email", email).update()
            }
        }

        val session = auth.register(email = email)

        mockMvc.get("/api/v1/test-probe/me") {
            header(HttpHeaders.AUTHORIZATION, session.bearer)
        }.andExpect {
            jsonPath("$.role") { value("teacher") }
        }
    }

    @Test
    fun `emails are stored in lower case`() {
        val email = auth.newEmail()

        auth.startRegistration(email = email.uppercase()).andExpect {
            status { isAccepted() }
            jsonPath("$.email") { value(email) }
        }
        assertThat(emails.sentTo(email)).hasSize(1)
    }

    @Test
    fun `only Polytechnic addresses may sign up`() {
        auth.startRegistration(email = "someone@gmail.com").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("validation_failed") }
            jsonPath("$.errors[0].field") { value("email") }
        }
    }

    @Test
    fun `weak passwords are rejected`() {
        val weakPasswords = listOf(
            "Ab1!2xy",
            "abcd12!xyz",
            "ABCD12!XYZ",
            "Abcde1!xyz",
            "Abcde12xyz",
            "Ab12!" + "x".repeat(68),
            // 39 characters but 73 bytes: BCrypt counts bytes.
            "Ab12!" + "ա".repeat(34),
        )
        weakPasswords.forEach { password ->
            auth.startRegistration(email = auth.newEmail(), password = password).andExpect {
                status { isBadRequest() }
                jsonPath("$.errors[0].field") { value("password") }
            }
        }
    }

    @Test
    fun `a blank name is rejected`() {
        auth.startRegistration(email = auth.newEmail(), fullName = " ").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("full_name") }
        }
    }

    @Test
    fun `a registered email cannot sign up again`() {
        auth.startRegistration(email = "hakobkarapetyan.tt319@polytechnic.am").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("email_taken") }
        }
    }

    @Test
    fun `a new code waits out the cooldown and replaces the old one`() {
        val email = auth.newEmail()
        auth.startRegistration(email).andExpect { status { isAccepted() } }
        val firstCode = emails.lastCodeFor(email)

        auth.resend(email).andExpect {
            status { isTooManyRequests() }
            jsonPath("$.code") { value("too_many_requests") }
            header { exists(HttpHeaders.RETRY_AFTER) }
        }
        auth.startRegistration(email).andExpect { status { isTooManyRequests() } }

        clock.advance(Duration.ofMinutes(2).plusSeconds(1))
        auth.resend(email).andExpect { status { isAccepted() } }
        val secondCode = emails.lastCodeFor(email)

        if (firstCode != secondCode) {
            auth.verify(email, firstCode).andExpect { jsonPath("$.code") { value("invalid_code") } }
        }
        auth.verify(email, secondCode).andExpect { status { isCreated() } }
    }

    @Test
    fun `five wrong codes burn the code`() {
        val email = auth.newEmail()
        auth.startRegistration(email)
        val code = emails.lastCodeFor(email)
        val wrongCode = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

        repeat(4) {
            auth.verify(email, wrongCode).andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("invalid_code") }
            }
        }
        auth.verify(email, wrongCode).andExpect { jsonPath("$.code") { value("too_many_attempts") } }
        auth.verify(email, code).andExpect { jsonPath("$.code") { value("too_many_attempts") } }
    }

    @Test
    fun `a code stops working after ten minutes`() {
        val email = auth.newEmail()
        auth.startRegistration(email)
        clock.advance(Duration.ofMinutes(10).plusSeconds(1))

        auth.verify(email, emails.lastCodeFor(email)).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("code_expired") }
        }
    }

    @Test
    fun `verifying or resending an unknown signup is not found`() {
        val email = auth.newEmail()

        auth.verify(email, "123456").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("registration_not_found") }
        }
        auth.resend(email).andExpect { status { isNotFound() } }
    }

    @Test
    fun `an email registered while the code was pending cannot be verified`() {
        val email = auth.newEmail()
        auth.startRegistration(email)
        transactionTemplate.executeWithoutResult {
            jdbcClient.sql("insert into users (email, username, password_hash, role_id) values (:email, 'Other', 'x', 1)")
                .param("email", email)
                .update()
        }

        auth.verify(email, emails.lastCodeFor(email)).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("email_taken") }
        }
    }

    private fun pendingCount(email: String): Long {
        return jdbcClient.sql("select count(*) from pending_registrations where email = :email")
            .param("email", email)
            .query(Long::class.java)
            .single()
    }
}
