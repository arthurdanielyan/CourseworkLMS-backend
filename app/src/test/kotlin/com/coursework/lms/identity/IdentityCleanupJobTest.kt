package com.coursework.lms.identity

import com.coursework.lms.support.AuthFlows
import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration

class IdentityCleanupJobTest : IntegrationTest() {

    @Autowired
    private lateinit var auth: AuthFlows

    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var job: IdentityCleanupJob

    @BeforeEach
    fun resetClock() {
        clock.reset()
    }

    @Test
    fun `expired tokens and stale signups go, live ones stay`() {
        val email = auth.newEmail()
        val oldSession = auth.register(email = email)
        auth.refresh(oldSession.refreshToken).andExpect { status { isOk() } }
        val stalePending = auth.newEmail()
        auth.startRegistration(stalePending).andExpect { status { isAccepted() } }

        clock.advance(Duration.ofDays(31))
        auth.login(email, AuthFlows.PASSWORD).andExpect { status { isOk() } }
        val freshPending = auth.newEmail()
        auth.startRegistration(freshPending).andExpect { status { isAccepted() } }

        job.run()

        assertThat(refreshTokensOf(email)).isEqualTo(1)
        assertThat(pendingCount(stalePending)).isZero()
        assertThat(pendingCount(freshPending)).isEqualTo(1)
    }

    @Test
    fun `a signup whose code just expired can still ask for a new one`() {
        val email = auth.newEmail()
        auth.startRegistration(email)
        clock.advance(Duration.ofHours(1))

        job.run()

        auth.resend(email).andExpect { status { isAccepted() } }
    }

    private fun refreshTokensOf(email: String): Long {
        return jdbcClient.sql("select count(*) from refresh_tokens t join users u on u.id = t.user_id where u.email = :email")
            .param("email", email)
            .query(Long::class.java)
            .single()
    }

    private fun pendingCount(email: String): Long {
        return jdbcClient.sql("select count(*) from pending_registrations where email = :email")
            .param("email", email)
            .query(Long::class.java)
            .single()
    }
}
