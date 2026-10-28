package com.coursework.lms.circulation

import com.coursework.lms.circulation.db.WaitlistTable
import com.coursework.lms.circulation.waitlist.OfferExpiryJob
import com.coursework.lms.core.db.DbUserContext
import com.coursework.lms.core.security.CurrentUser
import com.coursework.lms.core.testing.assertSchemaMatches
import com.coursework.lms.support.AsUser
import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.MutableClock
import com.coursework.lms.support.SeedUsers
import org.assertj.core.api.AbstractThrowableAssert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.time.Duration
import java.time.LocalDate

// Each test squeezes one book down to its last copy and gives the copies back at the end.
class WaitlistTest : IntegrationTest() {

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var asUser: AsUser

    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var offerExpiryJob: OfferExpiryJob

    @Autowired
    private lateinit var dbUserContext: DbUserContext

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private val json = JsonMapper.builder().build()

    @BeforeEach
    @AfterEach
    fun resetClock() {
        clock.reset()
    }

    @Test
    fun `the waitlist table matches the migrated schema`() {
        assertSchemaMatches(WaitlistTable)
    }

    @Test
    fun `nobody waits for a book with free copies`() {
        val waiting = jdbcClient.sql(
            "select count(*) from waitlist w join books b on b.id = w.book_id where b.copies_available > 0",
        ).query(Int::class.java).single()
        assertThat(waiting).isZero()

        post("/api/v1/books/${freeBook()}/waitlist", SeedUsers.student).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("book_available") }
        }
    }

    @Test
    fun `a returned copy is offered down the line before it goes back on the shelf`() {
        val bookId = freeBook()
        val copiesBefore = copiesAvailable(bookId)
        setCopiesAvailable(bookId, 1)
        try {
            val first = reserve(bookId, SeedUsers.student).idOf()

            post("/api/v1/books/$bookId/waitlist", SeedUsers.otherStudent).andExpect {
                status { isCreated() }
                jsonPath("$.position") { value(1) }
                jsonPath("$.offer_expires_at") { value(null) }
            }
            post("/api/v1/books/$bookId/waitlist", SeedUsers.teacher).andExpect {
                jsonPath("$.position") { value(2) }
            }

            post("/api/v1/reservations/$first/cancel", SeedUsers.student)
            assertThat(copiesAvailable(bookId)).isZero()
            get("/api/v1/waitlist/mine", SeedUsers.otherStudent).andExpect {
                jsonPath("$.content[0].book.id") { value(bookId) }
                jsonPath("$.content[0].position") { value(null) }
                jsonPath("$.content[0].offer_expires_at") { exists() }
            }
            get("/api/v1/waitlist/mine", SeedUsers.teacher).andExpect {
                jsonPath("$.content[0].position") { value(1) }
            }
            reserve(bookId, SeedUsers.student).andExpect {
                jsonPath("$.code") { value("no_copies_available") }
            }

            val second = reserve(bookId, SeedUsers.otherStudent).andExpect {
                status { isCreated() }
                jsonPath("$.status") { value("reserved") }
            }.idOf()
            get("/api/v1/waitlist/mine", SeedUsers.otherStudent).andExpect {
                jsonPath("$.page.total_elements") { value(0) }
            }

            post("/api/v1/reservations/$second/cancel", SeedUsers.otherStudent)
            get("/api/v1/waitlist/mine", SeedUsers.teacher).andExpect {
                jsonPath("$.content[0].offer_expires_at") { exists() }
            }

            delete("/api/v1/books/$bookId/waitlist", SeedUsers.teacher).andExpect {
                status { isNoContent() }
            }
            assertThat(copiesAvailable(bookId)).isEqualTo(1)
        } finally {
            setCopiesAvailable(bookId, copiesBefore)
        }
    }

    @Test
    fun `an unanswered offer passes on after a day`() {
        val bookId = freeBook()
        val copiesBefore = copiesAvailable(bookId)
        setCopiesAvailable(bookId, 1)
        try {
            val reservationId = reserve(bookId, SeedUsers.student).idOf()
            post("/api/v1/books/$bookId/waitlist", SeedUsers.otherStudent)
            post("/api/v1/reservations/$reservationId/cancel", SeedUsers.student)

            clock.advance(Duration.ofHours(23))
            offerExpiryJob.run()
            assertThat(waitlistEntries(bookId)).isEqualTo(1)

            clock.advance(Duration.ofHours(2))
            offerExpiryJob.run()
            assertThat(waitlistEntries(bookId)).isZero()
            assertThat(copiesAvailable(bookId)).isEqualTo(1)
        } finally {
            setCopiesAvailable(bookId, copiesBefore)
        }
    }

    @Test
    fun `a user waits once per book and not for a book they hold`() {
        val bookId = freeBook()
        val copiesBefore = copiesAvailable(bookId)
        setCopiesAvailable(bookId, 1)
        try {
            val reservationId = reserve(bookId, SeedUsers.student).idOf()

            post("/api/v1/books/$bookId/waitlist", SeedUsers.student).andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("already_reserved") }
            }
            post("/api/v1/books/$bookId/waitlist", SeedUsers.otherStudent).andExpect { status { isCreated() } }
            post("/api/v1/books/$bookId/waitlist", SeedUsers.otherStudent).andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("already_waiting") }
            }

            delete("/api/v1/books/$bookId/waitlist", SeedUsers.otherStudent)
            post("/api/v1/reservations/$reservationId/cancel", SeedUsers.student)
        } finally {
            setCopiesAvailable(bookId, copiesBefore)
        }
    }

    @Test
    fun `the database keeps waitlist entries to their users`() {
        val bookId = freeBook()

        assertThatThrownBy {
            asUser.run(SeedUsers.student.id) { jdbc ->
                jdbc.sql("insert into waitlist (book_id, user_id) values (:book, 2)").param("book", bookId).update()
            }
        }.hasSqlState(CHECK_VIOLATION)
        assertThatThrownBy {
            asUser.run(SeedUsers.admin.id) { jdbc ->
                jdbc.sql("update books set copies_available = 0 where id = :book").param("book", bookId).update()
                jdbc.sql("insert into waitlist (book_id, user_id) values (:book, 3)").param("book", bookId).update()
            }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)
    }

    private fun today(): LocalDate {
        return LocalDate.ofInstant(clock.instant(), LIBRARY_ZONE)
    }

    private fun freeBook(): Int {
        return jdbcClient.sql(
            """
            select id from books b
            where copies_available > 1
              and not exists (
                  select 1 from reservations r where r.book_id = b.id and r.status in ('reserved', 'borrowed')
              )
              and not exists (select 1 from waitlist w where w.book_id = b.id)
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

    private fun waitlistEntries(bookId: Int): Int {
        return jdbcClient.sql("select count(*) from waitlist where book_id = :id")
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

    private fun reserve(
        bookId: Int,
        user: CurrentUser,
    ): ResultActionsDsl {
        return mockMvc.post("/api/v1/books/$bookId/reservations") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
            contentType = MediaType.APPLICATION_JSON
            content = """{"return_deadline": "${today().plusDays(1)}"}"""
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
        const val CHECK_VIOLATION = "23514"
    }
}
