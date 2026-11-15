package com.coursework.lms.identity

import com.coursework.lms.circulation.LIBRARY_ZONE
import com.coursework.lms.core.db.DbUserContext
import com.coursework.lms.core.security.CurrentUser
import com.coursework.lms.core.testing.assertSchemaMatches
import com.coursework.lms.identity.db.TeacherEmailsTable
import com.coursework.lms.support.AsUser
import com.coursework.lms.support.AuthFlows
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
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.time.LocalDate

// Users the tests change are registered by the tests; the seed accounts are only read.
class AdminToolsTest : IntegrationTest() {

    @Autowired
    private lateinit var auth: AuthFlows

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var asUser: AsUser

    @Autowired
    private lateinit var dbUserContext: DbUserContext

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private val json = JsonMapper.builder().build()

    private val admin: String
        get() = tokens.bearer(SeedUsers.admin)

    @Test
    fun `the teacher emails table matches the migrated schema`() {
        assertSchemaMatches(TeacherEmailsTable)
    }

    @Test
    fun `admins find users by name, email and role`() {
        get("/api/v1/users?q=HAKOB", admin).andExpect {
            status { isOk() }
            jsonPath("$.content[0].full_name") { value("Hakob Karapetyan") }
            jsonPath("$.content[0].role") { value("student") }
        }
        get("/api/v1/users?role=teacher&q=ella", admin).andExpect {
            jsonPath("$.content[0].email") { value("ellahovhannisyan@polytechnic.am") }
        }
        get("/api/v1/users", tokens.bearer(SeedUsers.student)).andExpect { status { isForbidden() } }
    }

    @Test
    fun `a role change shows up at the user's next login`() {
        val email = auth.newEmail()
        auth.register(email)
        val userId = userIdOf(email)

        put("/api/v1/users/$userId/role", admin, """{"role": "teacher"}""").andExpect {
            status { isOk() }
            jsonPath("$.role") { value("teacher") }
        }
        val session = auth.session(auth.login(email, AuthFlows.PASSWORD))
        get("/api/v1/me", session.bearer).andExpect { jsonPath("$.role") { value("teacher") } }

        put("/api/v1/users/$userId/role", admin, """{"role": "librarian"}""").andExpect { status { isBadRequest() } }
        put("/api/v1/users/${SeedUsers.admin.id}/role", admin, """{"role": "student"}""").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("own_account") }
        }
    }

    @Test
    fun `the last admin stays an admin`() {
        val email = auth.newEmail()
        auth.register(email)
        val someoneWithAdminRights = CurrentUser(
            id = userIdOf(email),
            role = "admin",
            permissions = SeedUsers.admin.permissions,
        )

        put("/api/v1/users/${SeedUsers.admin.id}/role", tokens.bearer(someoneWithAdminRights), """{"role": "student"}""")
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("last_admin") }
            }
    }

    @Test
    fun `a teacher email promotes its student, and removing it demotes them again`() {
        val email = auth.newEmail()
        auth.register(email)

        val entryId = post("/api/v1/teacher-emails", admin, """{"email": "${email.uppercase()}"}""").andExpect {
            status { isCreated() }
            jsonPath("$.email") { value(email) }
            jsonPath("$.account.role") { value("teacher") }
        }.idOf()
        post("/api/v1/teacher-emails", admin, """{"email": "$email"}""").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("already_listed") }
        }
        assertThat(roleOf(email)).isEqualTo("teacher")

        delete("/api/v1/teacher-emails/$entryId", admin).andExpect { status { isNoContent() } }
        assertThat(roleOf(email)).isEqualTo("student")
    }

    @Test
    fun `a deleted user's reviews stay, and a copy held for them passes on`() {
        val email = auth.newEmail()
        val user = auth.register(email)
        val holder = auth.register()
        val userId = userIdOf(email)
        val bookId = freeBook()
        val copiesBefore = copiesAvailable(bookId)
        setCopiesAvailable(bookId, 1)
        try {
            put("/api/v1/books/$bookId/reviews/mine", user.bearer, """{"stars": 4, "text": "Kept after deletion."}""")
                .andExpect { status { isOk() } }
            val reservationId = reserve(bookId, holder.bearer).idOf()
            post("/api/v1/books/$bookId/waitlist", user.bearer, "").andExpect { status { isCreated() } }
            post("/api/v1/reservations/$reservationId/cancel", holder.bearer, "")

            delete("/api/v1/users/$userId", admin).andExpect { status { isNoContent() } }

            val reviews = json.readTree(
                get("/api/v1/books/$bookId/reviews", tokens.bearer(SeedUsers.student)).andReturn().response.contentAsString,
            )["content"]
            val kept = (0 until reviews.size()).map { reviews[it] }.single { it["text"].asString() == "Kept after deletion." }
            assertThat(kept["author"].isNull).isTrue()
            assertThat(copiesAvailable(bookId)).isEqualTo(1)
            get("/api/v1/me", user.bearer).andExpect { status { isUnauthorized() } }
            auth.refresh(user.refreshToken).andExpect { jsonPath("$.code") { value("invalid_refresh_token") } }
        } finally {
            deleteReview(bookId)
            setCopiesAvailable(bookId, copiesBefore)
        }
    }

    @Test
    fun `a user with a borrowed copy, or the admin themselves, is not deleted`() {
        val email = auth.newEmail()
        val user = auth.register(email)
        val reservationId = reserve(freeBook(), user.bearer).idOf()
        post("/api/v1/reservations/$reservationId/hand-over", admin, "")
        try {
            delete("/api/v1/users/${userIdOf(email)}", admin).andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("has_borrowed_copies") }
            }
            delete("/api/v1/users/${SeedUsers.admin.id}", admin).andExpect {
                jsonPath("$.code") { value("own_account") }
            }
        } finally {
            post("/api/v1/reservations/$reservationId/return", admin, "")
        }
    }

    @Test
    fun `admins keep the category and language lists`() {
        val id = post("/api/v1/categories", admin, """{"name": "Robotics Test"}""").andExpect {
            status { isCreated() }
        }.idOf()
        try {
            post("/api/v1/categories", admin, """{"name": "robotics test"}""").andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("name_taken") }
            }
            put("/api/v1/categories/$id", admin, """{"name": "Robotics Tested"}""").andExpect {
                jsonPath("$.name") { value("Robotics Tested") }
            }
            delete("/api/v1/categories/2", admin).andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("in_use") }
            }
            delete("/api/v1/languages/1", admin).andExpect { jsonPath("$.code") { value("in_use") } }
            post("/api/v1/languages", tokens.bearer(SeedUsers.teacher), """{"name": "French"}""").andExpect {
                status { isForbidden() }
            }
        } finally {
            delete("/api/v1/categories/$id", admin).andExpect { status { isNoContent() } }
        }
        assertThatThrownBy {
            asUser.run(SeedUsers.teacher.id) { jdbc -> jdbc.sql("insert into categories (name) values ('Sneaky')").update() }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)
    }

    private fun userIdOf(email: String): Int {
        return jdbcClient.sql("select id from users where email = :email").param("email", email).query(Int::class.java).single()
    }

    private fun roleOf(email: String): String {
        return jdbcClient.sql("select r.name from users u join roles r on r.id = u.role_id where u.email = :email")
            .param("email", email)
            .query(String::class.java)
            .single()
    }

    private fun freeBook(): Int {
        return jdbcClient.sql(
            """
            select id from books b
            where copies_available > 1 and deleted_at is null
              and not exists (select 1 from waitlist w where w.book_id = b.id)
              and not exists (select 1 from book_reviews r where r.book_id = b.id and r.user_id is null)
            order by id limit 1
            """,
        ).query(Int::class.java).single()
    }

    private fun copiesAvailable(bookId: Int): Int {
        return jdbcClient.sql("select copies_available from books where id = :id")
            .param("id", bookId)
            .query(Int::class.java)
            .single()
    }

    private fun setCopiesAvailable(
        bookId: Int,
        copies: Int,
    ) {
        dbUserContext.runAs(SeedUsers.admin.id) {
            transactionTemplate.executeWithoutResult {
                jdbcClient.sql("update books set copies_available = :copies where id = :id")
                    .param("copies", copies)
                    .param("id", bookId)
                    .update()
            }
        }
    }

    // A Review without an author can only go as moderation, like any other user's.
    private fun deleteReview(bookId: Int) {
        val reviewId = jdbcClient.sql("select id from book_reviews where book_id = :book and user_id is null")
            .param("book", bookId)
            .query(Int::class.java)
            .optional()
        reviewId.ifPresent { delete("/api/v1/reviews/$it", admin) }
    }

    private fun reserve(
        bookId: Int,
        bearer: String,
    ): ResultActionsDsl {
        val tomorrow = LocalDate.now(LIBRARY_ZONE).plusDays(1)
        return post("/api/v1/books/$bookId/reservations", bearer, """{"return_deadline": "$tomorrow"}""")
    }

    private fun get(
        path: String,
        bearer: String,
    ): ResultActionsDsl {
        return mockMvc.get(path) { header(HttpHeaders.AUTHORIZATION, bearer) }
    }

    private fun post(
        path: String,
        bearer: String,
        body: String,
    ): ResultActionsDsl {
        return mockMvc.post(path) {
            header(HttpHeaders.AUTHORIZATION, bearer)
            if (body.isNotEmpty()) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }
    }

    private fun put(
        path: String,
        bearer: String,
        body: String,
    ): ResultActionsDsl {
        return mockMvc.put(path) {
            header(HttpHeaders.AUTHORIZATION, bearer)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
    }

    private fun delete(
        path: String,
        bearer: String,
    ): ResultActionsDsl {
        return mockMvc.delete(path) { header(HttpHeaders.AUTHORIZATION, bearer) }
    }

    private fun ResultActionsDsl.idOf(): Int {
        return json.readTree(andReturn().response.contentAsString)["id"].asInt()
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
