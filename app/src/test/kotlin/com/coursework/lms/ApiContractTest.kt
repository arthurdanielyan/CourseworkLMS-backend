package com.coursework.lms

import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.SeedUsers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

class ApiContractTest : IntegrationTest() {

    @Test
    fun `a page carries snake_case items and Spring-style page metadata`() {
        mockMvc.get("/api/v1/test-probe/roles?page=0&size=2") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.student))
        }.andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(2) }
            jsonPath("$.content[0].role_id") { value(1) }
            jsonPath("$.content[0].role_name") { value("student") }
            jsonPath("$.page.size") { value(2) }
            jsonPath("$.page.number") { value(0) }
            jsonPath("$.page.total_elements") { value(3) }
            jsonPath("$.page.total_pages") { value(2) }
        }
    }

    @Test
    fun `the last page holds the remainder`() {
        mockMvc.get("/api/v1/test-probe/roles?page=1&size=2") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.student))
        }.andExpect {
            jsonPath("$.content.length()") { value(1) }
            jsonPath("$.content[0].role_name") { value("admin") }
        }
    }

    @Test
    fun `a page larger than the cap is rejected`() {
        mockMvc.get("/api/v1/test-probe/roles?size=101") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.student))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("validation_failed") }
            jsonPath("$.errors[0].field") { value("size") }
        }
    }

    @Test
    fun `validation errors name fields the way the client sends them`() {
        mockMvc.post("/api/v1/test-probe/validate") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.student))
            contentType = MediaType.APPLICATION_JSON
            content = """{"display_name": ""}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("validation_failed") }
            jsonPath("$.errors[0].field") { value("display_name") }
        }
    }

    @Test
    fun `request bodies are read as snake_case`() {
        mockMvc.post("/api/v1/test-probe/validate") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.student))
            contentType = MediaType.APPLICATION_JSON
            content = """{"display_name": "Artur"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.display_name") { value("Artur") }
        }
    }

    @Test
    fun `the API docs name fields the way the client sends them`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.components.schemas.StartRegistrationRequest.properties.full_name") { exists() }
            jsonPath("$.components.schemas.TokenPairResponse.properties.access_token") { exists() }
            jsonPath("$.components.schemas.TokenPairResponse.properties.accessToken") { doesNotExist() }
        }
    }

    @Test
    fun `the API docs show realistic examples of JSON responses`() {
        mockMvc.get("/v3/api-docs").andExpect {
            jsonPath("$.components.schemas.StartRegistrationRequest.properties.email.example") {
                value("anna.petrosyan@polytechnic.am")
            }
            jsonPath("$.paths['/api/v1/auth/login'].post.responses['200'].content['application/json']") { exists() }
        }
    }

    @Test
    fun `the API docs mark only protected endpoints as needing a token`() {
        mockMvc.get("/v3/api-docs").andExpect {
            jsonPath("$.paths['/api/v1/auth/login'].post.security") { isEmpty() }
            jsonPath("$.paths['/api/v1/test-probe/me'].get.security") { doesNotExist() }
        }
    }
}
