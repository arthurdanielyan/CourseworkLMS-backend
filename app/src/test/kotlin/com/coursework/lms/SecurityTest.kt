package com.coursework.lms

import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.SeedUsers
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get

class SecurityTest : IntegrationTest() {

    @Test
    fun `a request without a token is challenged with a problem response`() {
        mockMvc.get("/api/v1/test-probe/me").andExpect {
            status { isUnauthorized() }
            header { string(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.status") { value(401) }
            jsonPath("$.code") { value("not_authenticated") }
        }
    }

    @Test
    fun `a malformed token is rejected as invalid`() {
        mockMvc.get("/api/v1/test-probe/me") {
            header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
        }.andExpect {
            status { isUnauthorized() }
            header { string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")) }
            jsonPath("$.code") { value("not_authenticated") }
        }
    }

    @Test
    fun `an expired token is rejected`() {
        mockMvc.get("/api/v1/test-probe/me") {
            header(HttpHeaders.AUTHORIZATION, tokens.expiredBearer(SeedUsers.student))
        }.andExpect {
            status { isUnauthorized() }
            header { string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")) }
        }
    }

    @Test
    fun `a token from another issuer is rejected`() {
        mockMvc.get("/api/v1/test-probe/me") {
            header(HttpHeaders.AUTHORIZATION, tokens.foreignIssuerBearer(SeedUsers.student))
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `a valid token without the permission is forbidden, not challenged`() {
        mockMvc.get("/api/v1/test-probe/admin-only") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.student))
        }.andExpect {
            status { isForbidden() }
            header { doesNotExist(HttpHeaders.WWW_AUTHENTICATE) }
            jsonPath("$.code") { value("access_denied") }
        }
    }

    @Test
    fun `a valid token with the permission is let through`() {
        mockMvc.get("/api/v1/test-probe/admin-only") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.admin))
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `health is public`() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    @Test
    fun `an unknown path answers not found with a code`() {
        mockMvc.get("/api/v1/nothing-here") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.student))
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("not_found") }
        }
    }
}
