package com.coursework.lms.catalog

import com.coursework.lms.circulation.LIBRARY_ZONE
import com.coursework.lms.core.security.CurrentUser
import com.coursework.lms.support.AsUser
import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.SeedUsers
import org.assertj.core.api.AbstractThrowableAssert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.time.LocalDate

// Every test deletes the books it adds, so the shared catalog stays as V1 seeded it.
class BookEditingTest : IntegrationTest() {

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var asUser: AsUser

    private val json = JsonMapper.builder().build()

    @Test
    fun `a teacher's new book is theirs, with no copies yet`() {
        val bookId = create(SeedUsers.teacher, bookJson(authors = """["robert c. martin", "Anna New-Author"]""")).andExpect {
            status { isCreated() }
            jsonPath("$.owner.id") { value(SeedUsers.teacher.id) }
            jsonPath("$.total_copies") { value(0) }
            jsonPath("$.available") { value(false) }
            jsonPath("$.max_loan_days") { value(14) }
            jsonPath("$.publisher.name") { value("Pearson") }
            jsonPath("$.authors[0].name") { value("Anna New-Author") }
            jsonPath("$.authors[1].name") { value("Robert C. Martin") }
        }.idOf()
        try {
            assertThat(count("select count(*) from authors where lower(name) = 'robert c. martin'")).isEqualTo(1)
            get("/api/v1/books/mine", SeedUsers.teacher).andExpect {
                jsonPath("$.page.total_elements") { value(count("select count(*) from books where added_by = 4")) }
            }
        } finally {
            delete("/api/v1/books/$bookId", SeedUsers.teacher).andExpect { status { isNoContent() } }
        }
        get("/api/v1/books/$bookId", SeedUsers.student).andExpect { status { isNotFound() } }
    }

    @Test
    fun `an admin's new book is a University Book with copies`() {
        val bookId = create(SeedUsers.admin, bookJson(extra = """, "total_copies": 3, "max_loan_days": 7""")).andExpect {
            status { isCreated() }
            jsonPath("$.owner") { value(null) }
            jsonPath("$.copies_available") { value(3) }
            jsonPath("$.max_loan_days") { value(7) }
        }.idOf()
        delete("/api/v1/books/$bookId", SeedUsers.admin).andExpect { status { isNoContent() } }
    }

    @Test
    fun `only admins set copies, and teachers edit only their own books`() {
        create(SeedUsers.teacher, bookJson(extra = """, "total_copies": 3""")).andExpect {
            status { isForbidden() }
        }
        create(SeedUsers.student, bookJson()).andExpect {
            status { isForbidden() }
        }
        val universityBook = count("select min(id) from books where added_by is null")
        put("/api/v1/books/$universityBook", SeedUsers.teacher, bookJson()).andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `a book needs a description and known references`() {
        create(SeedUsers.admin, bookJson(description = "  ")).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("validation_failed") }
            jsonPath("$.errors[0].field") { value("description") }
        }
        create(SeedUsers.admin, bookJson(languageId = 999)).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("bad_request") }
        }
    }

    @Test
    fun `added copies go to the waitlist first, and held ones cannot be removed`() {
        val bookId = create(SeedUsers.admin, bookJson(extra = """, "total_copies": 1""")).idOf()
        try {
            val reservationId = reserve(bookId, SeedUsers.student).idOf()
            post("/api/v1/books/$bookId/waitlist", SeedUsers.otherStudent).andExpect { status { isCreated() } }

            put("/api/v1/books/$bookId", SeedUsers.admin, bookJson(extra = """, "total_copies": 2""")).andExpect {
                status { isOk() }
                jsonPath("$.total_copies") { value(2) }
                jsonPath("$.copies_available") { value(0) }
            }
            get("/api/v1/waitlist/mine", SeedUsers.otherStudent).andExpect {
                jsonPath("$.content[0].offer_expires_at") { exists() }
            }
            put("/api/v1/books/$bookId", SeedUsers.admin, bookJson(extra = """, "total_copies": 1""")).andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("copies_below_holds") }
            }

            delete("/api/v1/books/$bookId/waitlist", SeedUsers.otherStudent)
            post("/api/v1/reservations/$reservationId/cancel", SeedUsers.student)
            assertThat(count("select copies_available from books where id = $bookId")).isEqualTo(2)
        } finally {
            delete("/api/v1/books/$bookId", SeedUsers.admin).andExpect { status { isNoContent() } }
        }
    }

    @Test
    fun `covers and PDFs are uploaded, served without a token and removed`() {
        val bookId = create(SeedUsers.teacher, bookJson(title = "Upload Test")).idOf()
        try {
            val coverUrl = upload(bookId, "cover", PNG, SeedUsers.teacher).andExpect {
                status { isOk() }
                jsonPath("$.cover_image_url") { exists() }
            }.field("cover_image_url")
            assertThat(coverUrl).contains("/api/v1/files/")
            mockMvc.get(coverUrl.substringAfter("http://localhost")).andExpect {
                status { isOk() }
                content { contentType("image/png") }
                content { bytes(PNG) }
            }

            val pdfUrl = upload(bookId, "pdf", PDF, SeedUsers.teacher).andExpect {
                jsonPath("$.pdf_url") { exists() }
            }.field("pdf_url")
            mockMvc.get(pdfUrl.substringAfter("http://localhost")).andExpect {
                content { contentType(MediaType.APPLICATION_PDF) }
                header { string(HttpHeaders.CONTENT_DISPOSITION, containsString("Upload")) }
            }
            get("/api/v1/books?q=upload test", SeedUsers.student).andExpect {
                jsonPath("$.content[0].has_pdf") { value(true) }
            }

            mockMvc.delete("/api/v1/books/$bookId/cover") {
                header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.teacher))
            }.andExpect {
                status { isOk() }
                jsonPath("$.cover_image_url") { value(null) }
            }
            mockMvc.get(coverUrl.substringAfter("http://localhost")).andExpect { status { isNotFound() } }
        } finally {
            delete("/api/v1/books/$bookId", SeedUsers.teacher)
        }
        assertThat(count("select count(*) from files f where not exists (select 1 from books b where b.cover_file_id = f.id or b.pdf_file_id = f.id)"))
            .isZero()
    }

    @Test
    fun `uploads are checked by their contents and size`() {
        val bookId = create(SeedUsers.teacher, bookJson()).idOf()
        try {
            upload(bookId, "cover", "not an image".toByteArray(), SeedUsers.teacher).andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("unsupported_file_type") }
            }
            upload(bookId, "pdf", PNG, SeedUsers.teacher).andExpect {
                jsonPath("$.code") { value("unsupported_file_type") }
            }
            upload(bookId, "cover", PNG + ByteArray(6 * 1024 * 1024), SeedUsers.teacher).andExpect {
                status { isPayloadTooLarge() }
                jsonPath("$.code") { value("file_too_large") }
            }
        } finally {
            delete("/api/v1/books/$bookId", SeedUsers.teacher)
        }
    }

    @Test
    fun `a book with a borrowed copy is hidden until it comes back, then removed`() {
        val bookId = create(SeedUsers.admin, bookJson(extra = """, "total_copies": 1""")).idOf()
        val reservationId = reserve(bookId, SeedUsers.student).idOf()
        post("/api/v1/reservations/$reservationId/hand-over", SeedUsers.admin)

        delete("/api/v1/books/$bookId", SeedUsers.admin).andExpect { status { isNoContent() } }
        get("/api/v1/books/$bookId", SeedUsers.student).andExpect { status { isNotFound() } }
        reserve(bookId, SeedUsers.otherStudent).andExpect { status { isNotFound() } }
        get("/api/v1/reservations/mine?status=borrowed", SeedUsers.student).andExpect {
            jsonPath("$.content[?(@.book.id == $bookId)]") { exists() }
        }

        post("/api/v1/reservations/$reservationId/return", SeedUsers.admin).andExpect { status { isOk() } }
        assertThat(count("select count(*) from books where id = $bookId")).isZero()
    }

    @Test
    fun `a teacher cannot delete a book the library holds copies of`() {
        val bookId = create(SeedUsers.teacher, bookJson()).idOf()
        try {
            put("/api/v1/books/$bookId", SeedUsers.admin, bookJson(extra = """, "total_copies": 2""")).andExpect {
                status { isOk() }
            }
            delete("/api/v1/books/$bookId", SeedUsers.teacher).andExpect { status { isForbidden() } }
        } finally {
            delete("/api/v1/books/$bookId", SeedUsers.admin).andExpect { status { isNoContent() } }
        }
    }

    @Test
    fun `authors and publishers autocomplete by any part of the name`() {
        get("/api/v1/authors?q=MARTIN", SeedUsers.teacher).andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("Martin Fowler") }
            jsonPath("$[1].name") { value("Robert C. Martin") }
        }
        get("/api/v1/publishers?q=pear", SeedUsers.teacher).andExpect {
            jsonPath("$[0].name") { value("Pearson") }
        }
    }

    @Test
    fun `the database keeps teachers away from other people's book links`() {
        val universityBook = count("select min(id) from books where added_by is null")

        assertThatThrownBy {
            asUser.run(SeedUsers.teacher.id) { jdbc ->
                jdbc.sql("delete from books_categories where book_id = :book").param("book", universityBook).update()
            }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)
        assertThatThrownBy {
            asUser.run(SeedUsers.student.id) { jdbc ->
                jdbc.sql("insert into authors (name) values ('Student Author')").update()
            }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)
    }

    private fun bookJson(
        title: String = "Temporary Book",
        description: String = "A book the tests add and delete again.",
        authors: String = """["Robert C. Martin"]""",
        languageId: Int = 1,
        extra: String = "",
    ): String {
        return """
            {"title": "$title", "description": "$description", "authors": $authors, "publisher": "pearson",
             "language_id": $languageId, "category_ids": [2], "publication_year": 2008$extra}
        """.trimIndent()
    }

    private fun create(
        user: CurrentUser,
        body: String,
    ): ResultActionsDsl {
        return mockMvc.post("/api/v1/books") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
    }

    private fun put(
        path: String,
        user: CurrentUser,
        body: String,
    ): ResultActionsDsl {
        return mockMvc.put(path) {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
    }

    private fun upload(
        bookId: Int,
        kind: String,
        bytes: ByteArray,
        user: CurrentUser,
    ): ResultActionsDsl {
        return mockMvc.multipart(HttpMethod.PUT, "/api/v1/books/$bookId/$kind") {
            file("file", bytes)
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
        }
    }

    private fun reserve(
        bookId: Int,
        user: CurrentUser,
    ): ResultActionsDsl {
        val tomorrow = LocalDate.now(LIBRARY_ZONE).plusDays(1)
        return mockMvc.post("/api/v1/books/$bookId/reservations") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
            contentType = MediaType.APPLICATION_JSON
            content = """{"return_deadline": "$tomorrow"}"""
        }
    }

    private fun get(
        path: String,
        user: CurrentUser,
    ): ResultActionsDsl {
        return mockMvc.get(path) {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
        }
    }

    private fun post(
        path: String,
        user: CurrentUser,
    ): ResultActionsDsl {
        return mockMvc.post(path) {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
        }
    }

    private fun delete(
        path: String,
        user: CurrentUser,
    ): ResultActionsDsl {
        return mockMvc.delete(path) {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
        }
    }

    private fun count(sql: String): Int {
        return jdbcClient.sql(sql).query(Int::class.java).single()
    }

    private fun ResultActionsDsl.idOf(): Int {
        return json.readTree(andReturn().response.contentAsString)["id"].asInt()
    }

    private fun ResultActionsDsl.field(name: String): String {
        return json.readTree(andReturn().response.contentAsString)[name].asString()
    }

    private fun AbstractThrowableAssert<*, *>.hasSqlState(sqlState: String) {
        satisfies({ error ->
            val states = generateSequence(error) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }
            assertThat(states.toList()).contains(sqlState)
        })
    }

    private companion object {
        const val INSUFFICIENT_PRIVILEGE = "42501"
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
        val PDF = "%PDF-1.4 test document".toByteArray()
    }
}
