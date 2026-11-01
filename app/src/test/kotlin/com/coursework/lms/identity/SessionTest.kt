package com.coursework.lms.identity

import com.coursework.lms.core.db.DbUserContext
import com.coursework.lms.support.AuthFlows
import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.MutableClock
import com.coursework.lms.support.SeedUsers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

class SessionTest : IntegrationTest() {

    @Autowired
    private lateinit var auth: AuthFlows

    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var userContext: DbUserContext

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @BeforeEach
    fun resetClock() {
        clock.reset()
    }

    @Test
    fun `a registered user logs in with their password`() {
        val email = auth.newEmail()
        auth.register(email = email)

        val session = auth.session(
            auth.login(email.uppercase(), AuthFlows.PASSWORD).andExpect { status { isOk() } },
        )

        mockMvc.get("/api/v1/test-probe/me") {
            header(HttpHeaders.AUTHORIZATION, session.bearer)
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `seed users log in with their original passwords`() {
        auth.login("arthurdanielyan.tt319@polytechnic.am", "qwertyuiop").andExpect { status { isOk() } }
    }

    @Test
    fun `a wrong password and an unknown email fail the same way`() {
        val email = auth.newEmail()
        auth.register(email = email)

        auth.login(email, "Wrong-passw0rd1").andExpect {
            status { isBadRequest() }
            header { doesNotExist(HttpHeaders.WWW_AUTHENTICATE) }
            jsonPath("$.code") { value("invalid_credentials") }
        }
        auth.login(auth.newEmail(), AuthFlows.PASSWORD).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_credentials") }
        }
    }

    @Test
    fun `a refresh token is replaced by the one it is exchanged for`() {
        val session = auth.register()

        val renewed = auth.session(auth.refresh(session.refreshToken).andExpect { status { isOk() } })

        mockMvc.get("/api/v1/test-probe/me") {
            header(HttpHeaders.AUTHORIZATION, renewed.bearer)
        }.andExpect {
            status { isOk() }
        }
        auth.refresh(renewed.refreshToken).andExpect { status { isOk() } }
    }

    @Test
    fun `reusing a rotated refresh token ends every session of the user`() {
        val session = auth.register()
        val renewed = auth.session(auth.refresh(session.refreshToken))

        auth.refresh(session.refreshToken).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_refresh_token") }
        }
        auth.refresh(renewed.refreshToken).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_refresh_token") }
        }
    }

    @Test
    fun `a refresh token expires after thirty days`() {
        val session = auth.register()
        clock.advance(Duration.ofDays(30).plusSeconds(1))

        auth.refresh(session.refreshToken).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_refresh_token") }
        }
    }

    @Test
    fun `an unknown refresh token is rejected`() {
        auth.refresh("not-a-refresh-token").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_refresh_token") }
        }
    }

    @Test
    fun `a refreshed session carries the user's new role`() {
        val email = auth.newEmail()
        val session = auth.register(email = email)
        userContext.runAs(SeedUsers.admin.id) {
            transactionTemplate.executeWithoutResult {
                jdbcClient.sql("update users set role_id = (select id from roles where name = 'teacher') where email = :email")
                    .param("email", email)
                    .update()
            }
        }

        val renewed = auth.session(auth.refresh(session.refreshToken))

        mockMvc.get("/api/v1/test-probe/me") {
            header(HttpHeaders.AUTHORIZATION, renewed.bearer)
        }.andExpect {
            jsonPath("$.role") { value("teacher") }
        }
    }
}
