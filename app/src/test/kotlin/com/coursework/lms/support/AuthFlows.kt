package com.coursework.lms.support

import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** The account-access endpoints as a client calls them, for tests that need a registered, logged-in user. */
@Component
class AuthFlows(
    private val mockMvc: MockMvc,
    private val emails: RecordingEmailSender,
    private val jsonMapper: JsonMapper,
) {

    /** Tokens of a logged-in session. */
    data class Session(
        val accessToken: String,
        val refreshToken: String,
    ) {

        val bearer: String
            get() = "Bearer $accessToken"
    }

    fun newEmail(): String {
        return "user.${UUID.randomUUID().toString().take(12)}@polytechnic.am"
    }

    fun startRegistration(
        email: String,
        fullName: String = "Test User",
        password: String = PASSWORD,
    ): ResultActionsDsl {
        return post(
            "/api/v1/auth/registrations",
            mapOf("email" to email, "full_name" to fullName, "password" to password),
        )
    }

    fun resend(email: String): ResultActionsDsl {
        return post("/api/v1/auth/registrations/resend", mapOf("email" to email))
    }

    fun verify(
        email: String,
        code: String,
    ): ResultActionsDsl {
        return post("/api/v1/auth/registrations/verify", mapOf("email" to email, "code" to code))
    }

    fun login(
        email: String,
        password: String,
    ): ResultActionsDsl {
        return post("/api/v1/auth/login", mapOf("email" to email, "password" to password))
    }

    fun refresh(refreshToken: String): ResultActionsDsl {
        return post("/api/v1/auth/refresh", mapOf("refresh_token" to refreshToken))
    }

    /** Signs [email] up through the whole flow and returns the session the verification starts. */
    fun register(
        email: String = newEmail(),
        password: String = PASSWORD,
    ): Session {
        startRegistration(email = email, password = password).andExpect { status { isAccepted() } }
        return session(verify(email, emails.lastCodeFor(email)))
    }

    fun session(result: ResultActionsDsl): Session {
        val body = jsonMapper.readTree(result.andReturn().response.contentAsString)
        return Session(
            accessToken = body.path("access_token").asString(),
            refreshToken = body.path("refresh_token").asString(),
        )
    }

    private fun post(
        path: String,
        body: Map<String, String>,
    ): ResultActionsDsl {
        return mockMvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(body)
        }
    }

    companion object {
        const val PASSWORD = "Passw0rd1!"
    }
}
