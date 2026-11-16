package com.coursework.lms.engagement

import com.coursework.lms.core.security.CurrentUser
import com.coursework.lms.engagement.db.UsersFavouritesTable
import com.coursework.lms.core.testing.assertSchemaMatches
import com.coursework.lms.support.AsUser
import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.SeedUsers
import org.assertj.core.api.AbstractThrowableAssert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.sql.SQLException

// The test database seeds the student's Favourites as books 1, 61 and 101, and the other student's as 2, 62 and 102.
class FavouritesTest : IntegrationTest() {

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var asUser: AsUser

    @Test
    fun `the favourites table matches the migrated schema`() {
        assertSchemaMatches(UsersFavouritesTable)
    }

    @Test
    fun `the list holds the user's own Favourites`() {
        get("/api/v1/favourites", SeedUsers.student).andExpect {
            status { isOk() }
            jsonPath("$.page.total_elements") { value(3) }
            jsonPath("$.content[0].id") { value(101) }
            jsonPath("$.content[1].id") { value(61) }
            jsonPath("$.content[2].id") { value(1) }
            jsonPath("$.content[0].favourite") { value(true) }
        }
    }

    @Test
    fun `a new Favourite comes first until it is removed`() {
        try {
            put(5, SeedUsers.otherStudent).andExpect { status { isNoContent() } }

            get("/api/v1/favourites", SeedUsers.otherStudent).andExpect {
                jsonPath("$.page.total_elements") { value(4) }
                jsonPath("$.content[0].id") { value(5) }
            }
            get("/api/v1/books/5", SeedUsers.otherStudent).andExpect {
                jsonPath("$.favourite") { value(true) }
            }
        } finally {
            delete(5, SeedUsers.otherStudent).andExpect { status { isNoContent() } }
        }

        get("/api/v1/favourites", SeedUsers.otherStudent).andExpect {
            jsonPath("$.page.total_elements") { value(3) }
        }
    }

    @Test
    fun `adding a Favourite twice keeps one`() {
        try {
            put(7, SeedUsers.otherStudent).andExpect { status { isNoContent() } }
            put(7, SeedUsers.otherStudent).andExpect { status { isNoContent() } }

            assertThat(favouriteRows(SeedUsers.otherStudent.id, 7)).isEqualTo(1)
        } finally {
            delete(7, SeedUsers.otherStudent)
        }
    }

    @Test
    fun `an unknown book cannot become a Favourite`() {
        put(999999, SeedUsers.otherStudent).andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("not_found") }
        }
    }

    @Test
    fun `removing a book that is not a Favourite changes nothing`() {
        delete(8, SeedUsers.otherStudent).andExpect { status { isNoContent() } }
    }

    @Test
    fun `books say whether the logged-in user favoured them`() {
        get("/api/v1/books/1", SeedUsers.student).andExpect {
            jsonPath("$.favourite") { value(true) }
        }
        get("/api/v1/books/1", SeedUsers.otherStudent).andExpect {
            jsonPath("$.favourite") { value(false) }
        }
        get("/api/v1/books?q=clean code", SeedUsers.student).andExpect {
            jsonPath("$.content[0].id") { value(1) }
            jsonPath("$.content[0].favourite") { value(true) }
        }
    }

    @Test
    fun `the database lets users change only their own Favourites`() {
        assertThatThrownBy {
            asUser.run(SeedUsers.student.id) { jdbc ->
                jdbc.sql("insert into users_favourites (user_id, book_id) values (:user, 5)")
                    .param("user", SeedUsers.otherStudent.id)
                    .update()
            }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)

        assertThatThrownBy {
            asUser.run(SeedUsers.student.id) { jdbc ->
                jdbc.sql("delete from users_favourites where user_id = :user")
                    .param("user", SeedUsers.otherStudent.id)
                    .update()
            }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)
    }

    @Test
    fun `deleting a book takes its Favourites with it`() {
        val remaining = asUser.run(SeedUsers.admin.id) { jdbc ->
            val bookId = jdbc.sql(
                """
                insert into books (title, description, total_copies, copies_available, publisher_id, language_id,
                                   max_reservation_days)
                values ('Temporary', 'A book that is deleted again.', 0, 0, 1, 1, 14)
                returning id
                """,
            ).query(Int::class.java).single()
            jdbc.sql("insert into users_favourites (user_id, book_id) values (:user, :book)")
                .param("user", SeedUsers.admin.id)
                .param("book", bookId)
                .update()
            jdbc.sql("delete from books where id = :book").param("book", bookId).update()
            jdbc.sql("select count(*) from users_favourites where book_id = :book")
                .param("book", bookId)
                .query(Int::class.java)
                .single()
        }

        assertThat(remaining).isZero()
    }

    private fun get(
        path: String,
        user: CurrentUser,
    ): ResultActionsDsl {
        return mockMvc.get(path) {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
        }
    }

    private fun put(
        bookId: Int,
        user: CurrentUser,
    ): ResultActionsDsl {
        return mockMvc.put("/api/v1/favourites/$bookId") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
        }
    }

    private fun delete(
        bookId: Int,
        user: CurrentUser,
    ): ResultActionsDsl {
        return mockMvc.delete("/api/v1/favourites/$bookId") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
        }
    }

    private fun favouriteRows(
        userId: Int,
        bookId: Int,
    ): Int {
        return jdbcClient.sql("select count(*) from users_favourites where user_id = :user and book_id = :book")
            .param("user", userId)
            .param("book", bookId)
            .query(Int::class.java)
            .single()
    }

    private fun AbstractThrowableAssert<*, *>.hasSqlState(sqlState: String) {
        satisfies({ error ->
            val states = generateSequence(error) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }
            assertThat(states.toList()).contains(sqlState)
        })
    }

    private companion object {
        const val INSUFFICIENT_PRIVILEGE = "42501"
    }
}
