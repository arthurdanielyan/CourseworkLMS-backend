package com.coursework.lms.engagement

import com.coursework.lms.core.security.CurrentUser
import com.coursework.lms.core.testing.assertSchemaMatches
import com.coursework.lms.engagement.db.BookReviewsTable
import com.coursework.lms.support.AsUser
import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.SeedUsers
import org.assertj.core.api.AbstractThrowableAssert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.sql.SQLException

// V1 seeds three Reviews of book 1: 5 stars by the student, 4 by the other student and 5 by the teacher, written in
// that order.
class ReviewsTest : IntegrationTest() {

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var asUser: AsUser

    @Test
    fun `the reviews table matches the migrated schema`() {
        assertSchemaMatches(BookReviewsTable)
    }

    @Test
    fun `a book's Reviews come newest first`() {
        get("/api/v1/books/1/reviews", SeedUsers.student).andExpect {
            status { isOk() }
            jsonPath("$.page.total_elements") { value(3) }
            jsonPath("$.content[0].author.id") { value(SeedUsers.teacher.id) }
            jsonPath("$.content[2].author.name") { value("Hakob Karapetyan") }
            jsonPath("$.content[2].stars") { value(5) }
            jsonPath("$.content[2].mine") { value(true) }
            jsonPath("$.content[0].mine") { value(false) }
        }
    }

    @Test
    fun `the stars filter keeps the matching Reviews`() {
        get("/api/v1/books/1/reviews?stars=4", SeedUsers.student).andExpect {
            jsonPath("$.page.total_elements") { value(1) }
            jsonPath("$.content[0].author.id") { value(SeedUsers.otherStudent.id) }
        }
        get("/api/v1/books/1/reviews?stars=1&stars=2", SeedUsers.student).andExpect {
            jsonPath("$.page.total_elements") { value(0) }
        }
    }

    @Test
    fun `the summary counts Reviews per star and finds the user's own`() {
        get("/api/v1/books/1/reviews/summary", SeedUsers.student).andExpect {
            status { isOk() }
            jsonPath("$.total") { value(3) }
            jsonPath("$.average") { value(4.7) }
            jsonPath("$.five_stars") { value(2) }
            jsonPath("$.four_stars") { value(1) }
            jsonPath("$.one_star") { value(0) }
            jsonPath("$.my_review.stars") { value(5) }
        }
    }

    @Test
    fun `writing, rewriting and deleting a Review keeps the book's rating in step`() {
        val bookId = unreviewedBook()
        try {
            writeMine(bookId, SeedUsers.otherStudent, """{"stars": 4, "text": "  Useful.  "}""").andExpect {
                status { isOk() }
                jsonPath("$.stars") { value(4) }
                jsonPath("$.text") { value("Useful.") }
                jsonPath("$.mine") { value(true) }
            }
            writeMine(bookId, SeedUsers.otherStudent, """{"stars": 2}""").andExpect {
                jsonPath("$.stars") { value(2) }
                jsonPath("$.text") { value(null) }
            }
            get("/api/v1/books/$bookId/reviews/summary", SeedUsers.student).andExpect {
                jsonPath("$.total") { value(1) }
                jsonPath("$.two_stars") { value(1) }
                jsonPath("$.my_review") { value(null) }
            }
            get("/api/v1/books/$bookId", SeedUsers.student).andExpect {
                jsonPath("$.rating") { value(2.0) }
            }
        } finally {
            deleteMine(bookId, SeedUsers.otherStudent).andExpect { status { isNoContent() } }
        }

        get("/api/v1/books/$bookId", SeedUsers.student).andExpect {
            jsonPath("$.rating") { value(null) }
        }
        get("/api/v1/books/$bookId/reviews/summary", SeedUsers.student).andExpect {
            jsonPath("$.total") { value(0) }
            jsonPath("$.average") { value(null) }
        }
    }

    @Test
    fun `stars outside one to five are rejected`() {
        writeMine(unreviewedBook(), SeedUsers.otherStudent, """{"stars": 6}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("validation_failed") }
            jsonPath("$.errors[0].field") { value("stars") }
        }
    }

    @Test
    fun `reviews of an unknown book are not found`() {
        get("/api/v1/books/999999/reviews", SeedUsers.student).andExpect {
            status { isNotFound() }
        }
        writeMine(999999, SeedUsers.student, """{"stars": 5}""").andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `only admins delete other people's Reviews`() {
        val bookId = unreviewedBook()
        writeMine(bookId, SeedUsers.otherStudent, """{"stars": 1, "text": "Spam"}""")
        val reviewId = reviewIdOf(SeedUsers.otherStudent, bookId)

        delete("/api/v1/reviews/$reviewId", SeedUsers.student).andExpect {
            status { isForbidden() }
        }
        delete("/api/v1/reviews/$reviewId", SeedUsers.admin).andExpect {
            status { isNoContent() }
        }
        delete("/api/v1/reviews/$reviewId", SeedUsers.admin).andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `the database keeps Reviews to their authors`() {
        val othersReview = reviewIdOf(SeedUsers.otherStudent, 1)

        assertThatThrownBy {
            asUser.run(SeedUsers.student.id) { jdbc ->
                jdbc.sql("insert into book_reviews (user_id, book_id, stars) values (:user, 5, 5)")
                    .param("user", SeedUsers.otherStudent.id)
                    .update()
            }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)
        assertThatThrownBy {
            asUser.run(SeedUsers.student.id) { jdbc ->
                jdbc.sql("update book_reviews set stars = 1 where id = :id").param("id", othersReview).update()
            }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)
        assertThatThrownBy {
            asUser.run(SeedUsers.student.id) { jdbc ->
                jdbc.sql("delete from book_reviews where id = :id").param("id", othersReview).update()
            }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)
    }

    @Test
    fun `the database allows one Review per user and book, with one to five stars`() {
        assertThatThrownBy {
            asUser.run(SeedUsers.student.id) { jdbc ->
                jdbc.sql("insert into book_reviews (user_id, book_id, stars) values (:user, 1, 3)")
                    .param("user", SeedUsers.student.id)
                    .update()
            }
        }.hasSqlState(UNIQUE_VIOLATION)
        assertThatThrownBy {
            asUser.run(SeedUsers.student.id) { jdbc ->
                jdbc.sql("update book_reviews set stars = 0 where user_id = :user and book_id = 1")
                    .param("user", SeedUsers.student.id)
                    .update()
            }
        }.hasSqlState(CHECK_VIOLATION)
    }

    @Test
    fun `deleting a book takes its Reviews with it`() {
        val remaining = asUser.run(SeedUsers.admin.id) { jdbc ->
            val bookId = jdbc.sql(
                """
                insert into books (title, description, total_copies, copies_available, publisher_id, language_id,
                                   max_reservation_days)
                values ('Temporary', 'A book that is deleted again.', 0, 0, 1, 1, 14)
                returning id
                """,
            ).query(Int::class.java).single()
            jdbc.sql("insert into book_reviews (user_id, book_id, stars) values (:user, :book, 4)")
                .param("user", SeedUsers.admin.id)
                .param("book", bookId)
                .update()
            jdbc.sql("delete from books where id = :book").param("book", bookId).update()
            jdbc.sql("select count(*) from book_reviews where book_id = :book")
                .param("book", bookId)
                .query(Int::class.java)
                .single()
        }

        assertThat(remaining).isZero()
    }

    private fun unreviewedBook(): Int {
        return jdbcClient.sql(
            "select id from books b where not exists (select 1 from book_reviews r where r.book_id = b.id) order by id limit 1",
        ).query(Int::class.java).single()
    }

    private fun reviewIdOf(
        user: CurrentUser,
        bookId: Int,
    ): Int {
        return jdbcClient.sql("select id from book_reviews where user_id = :user and book_id = :book")
            .param("user", user.id)
            .param("book", bookId)
            .query(Int::class.java)
            .single()
    }

    private fun get(
        path: String,
        user: CurrentUser,
    ): ResultActionsDsl {
        return mockMvc.get(path) {
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

    private fun writeMine(
        bookId: Int,
        user: CurrentUser,
        body: String,
    ): ResultActionsDsl {
        return mockMvc.put("/api/v1/books/$bookId/reviews/mine") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
    }

    private fun deleteMine(
        bookId: Int,
        user: CurrentUser,
    ): ResultActionsDsl {
        return delete("/api/v1/books/$bookId/reviews/mine", user)
    }

    private fun AbstractThrowableAssert<*, *>.hasSqlState(sqlState: String) {
        satisfies({ error ->
            val states = generateSequence(error) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }
            assertThat(states.toList()).contains(sqlState)
        })
    }

    private companion object {
        const val INSUFFICIENT_PRIVILEGE = "42501"
        const val UNIQUE_VIOLATION = "23505"
        const val CHECK_VIOLATION = "23514"
    }
}
