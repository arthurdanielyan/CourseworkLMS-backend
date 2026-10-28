package com.coursework.lms.catalog

import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.SeedUsers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get

class BookDetailsTest : IntegrationTest() {

    @Test
    fun `a University Book has no owner`() {
        get("/api/v1/books/1").andExpect {
            status { isOk() }
            jsonPath("$.title") { value("Clean Code") }
            jsonPath("$.authors[0].name") { value("Anton Chekhov") }
            jsonPath("$.publisher.id") { value(2) }
            jsonPath("$.language.name") { value("English") }
            jsonPath("$.categories[0].name") { value("Software Engineering") }
            jsonPath("$.total_copies") { value(6) }
            jsonPath("$.copies_available") { value(6) }
            jsonPath("$.available") { value(true) }
            jsonPath("$.pdf_url") { value(null) }
            jsonPath("$.owner") { value(null) }
        }
    }

    @Test
    fun `a teacher's book names its owner`() {
        get("/api/v1/books/21").andExpect {
            jsonPath("$.owner.id") { value(SeedUsers.teacher.id) }
            jsonPath("$.owner.name") { value("Ella Hovhannisyan") }
            jsonPath("$.pdf_url") { value("https://archive.org/download/opensource/library_resource_21.pdf") }
            jsonPath("$.rating") { value(null) }
        }
    }

    @Test
    fun `an unknown book is not found`() {
        get("/api/v1/books/999999").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("not_found") }
        }
    }

    @Test
    fun `the filter lists are sorted by name`() {
        get("/api/v1/categories").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(20) }
            jsonPath("$[0].name") { value("Architecture") }
        }
        get("/api/v1/languages").andExpect {
            jsonPath("$.length()") { value(3) }
            jsonPath("$[0].name") { value("Armenian") }
            jsonPath("$[2].name") { value("Russian") }
        }
        get("/api/v1/books/owners").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].name") { value("Ella Hovhannisyan") }
        }
    }

    private fun get(path: String): ResultActionsDsl {
        return mockMvc.get(path) {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.student))
        }
    }
}
