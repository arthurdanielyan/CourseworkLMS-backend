package com.coursework.lms

import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.SeedUsers
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.post

class TriggerErrorMappingTest : IntegrationTest() {

    @Test
    fun `a trigger's access denial answers forbidden with the database's reason`() {
        mockMvc.post("/api/v1/test-probe/books") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.student))
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("access_denied") }
            jsonPath("$.detail") { value(containsString("permission to add books")) }
        }
    }

    @Test
    fun `a write the backend forgot to attribute is a server error, never a challenge`() {
        mockMvc.post("/api/v1/test-probe/public/books").andExpect {
            status { isInternalServerError() }
            header { doesNotExist(HttpHeaders.WWW_AUTHENTICATE) }
            jsonPath("$.code") { value("internal_error") }
        }
    }

    @Test
    fun `a teacher's new book is their own and starts without copies`() {
        mockMvc.post("/api/v1/test-probe/books") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.teacher))
        }.andExpect {
            status { isOk() }
            jsonPath("$.added_by") { value(SeedUsers.teacher.id) }
            jsonPath("$.total_copies") { value(0) }
            jsonPath("$.max_reservation_days") { value(14) }
        }
    }
}
