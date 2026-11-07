package com.coursework.lms.identity

import com.coursework.lms.support.AuthFlows
import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.SeedUsers
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

class AccountTest : IntegrationTest() {

    @Autowired
    private lateinit var auth: AuthFlows

    @Test
    fun `me tells the logged-in user who they are`() {
        val email = auth.newEmail()
        val session = auth.register(email)

        mockMvc.get("/api/v1/me") { header(HttpHeaders.AUTHORIZATION, session.bearer) }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value(email) }
            jsonPath("$.full_name") { value("Test User") }
            jsonPath("$.role") { value("student") }
            jsonPath("$.permissions[?(@ == 'reservations.create')]") { exists() }
        }
        mockMvc.get("/api/v1/me") { header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.teacher)) }.andExpect {
            jsonPath("$.full_name") { value("Ella Hovhannisyan") }
            jsonPath("$.role") { value("teacher") }
        }
        mockMvc.get("/api/v1/me").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a user renames themselves`() {
        val session = auth.register()

        send("/api/v1/me/name", session.bearer, """{"full_name": "  Anna Petrosyan  "}""", put = true).andExpect {
            status { isOk() }
            jsonPath("$.full_name") { value("Anna Petrosyan") }
        }
        send("/api/v1/me/name", session.bearer, """{"full_name": " "}""", put = true).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("full_name") }
        }
    }

    @Test
    fun `changing the password keeps this device logged in and logs out the others`() {
        val email = auth.newEmail()
        val thisDevice = auth.register(email)
        val otherDevice = auth.session(auth.login(email, AuthFlows.PASSWORD))

        send(
            "/api/v1/me/password",
            thisDevice.bearer,
            """{"current_password": "Wrong#2026", "new_password": "$NEW_PASSWORD"}""",
        ).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_credentials") }
        }
        val renewed = auth.session(
            send(
                "/api/v1/me/password",
                thisDevice.bearer,
                """{"current_password": "${AuthFlows.PASSWORD}", "new_password": "$NEW_PASSWORD"}""",
            ).andExpect { status { isOk() } },
        )

        auth.refresh(otherDevice.refreshToken).andExpect { jsonPath("$.code") { value("invalid_refresh_token") } }
        auth.refresh(renewed.refreshToken).andExpect { status { isOk() } }
        auth.login(email, NEW_PASSWORD).andExpect { status { isOk() } }
    }

    @Test
    fun `logging out ends that session only`() {
        val email = auth.newEmail()
        val phone = auth.register(email)
        val tablet = auth.session(auth.login(email, AuthFlows.PASSWORD))

        logout(phone.refreshToken).andExpect { status { isNoContent() } }

        auth.refresh(phone.refreshToken).andExpect { jsonPath("$.code") { value("invalid_refresh_token") } }
        auth.refresh(tablet.refreshToken).andExpect { status { isOk() } }
        logout("not-a-token").andExpect { status { isNoContent() } }
    }

    private fun send(
        path: String,
        bearer: String,
        body: String,
        put: Boolean = false,
    ): ResultActionsDsl {
        val request: MockHttpServletRequestDsl.() -> Unit = {
            header(HttpHeaders.AUTHORIZATION, bearer)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
        return if (put) mockMvc.put(path, dsl = request) else mockMvc.post(path, dsl = request)
    }

    private fun logout(refreshToken: String): ResultActionsDsl {
        return mockMvc.post("/api/v1/auth/logout") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refresh_token": "$refreshToken"}"""
        }
    }

    private companion object {
        const val NEW_PASSWORD = "Library#2027"
    }
}
