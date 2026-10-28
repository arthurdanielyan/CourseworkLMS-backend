package com.coursework.lms.circulation

import com.coursework.lms.circulation.db.ReservationsTable
import com.coursework.lms.circulation.reservation.ReservationExpiryJob
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.time.Duration
import java.time.LocalDate

// Reservations are kept as history, so every test ends its own with a cancel or a return, putting the copy back.
class ReservationsTest : IntegrationTest() {

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var asUser: AsUser

    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var expiryJob: ReservationExpiryJob

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
    fun `the reservations table matches the migrated schema`() {
        assertSchemaMatches(ReservationsTable)
    }

    @Test
    fun `the April reservations became returned and gave their copies back`() {
        val returned = jdbcClient.sql("select count(*) from reservations where id in (1, 2, 3, 11, 12) and status = 'returned'")
            .query(Int::class.java)
            .single()
        val book1 = jdbcClient.sql("select total_copies - copies_available from books where id = 1")
            .query(Int::class.java)
            .single()

        assertThat(returned).isEqualTo(5)
        assertThat(book1).isZero()
    }

    @Test
    fun `book details tell the longest loan`() {
        get("/api/v1/books/1", SeedUsers.student).andExpect {
            jsonPath("$.max_loan_days") { value(11) }
        }
    }

    @Test
    fun `reserving takes a copy and cancelling gives it back`() {
        val bookId = freeBook()
        val copiesBefore = copiesAvailable(bookId)

        val reservationId = reserve(bookId, SeedUsers.student, today().plusDays(1)).andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("reserved") }
            jsonPath("$.book.id") { value(bookId) }
            jsonPath("$.user.id") { value(SeedUsers.student.id) }
            jsonPath("$.return_deadline") { value(today().plusDays(1).toString()) }
            jsonPath("$.pickup_deadline") { exists() }
            jsonPath("$.overdue") { value(false) }
        }.idOf()
        assertThat(copiesAvailable(bookId)).isEqualTo(copiesBefore - 1)
        get("/api/v1/reservations/mine?status=reserved", SeedUsers.student).andExpect {
            jsonPath("$.content[0].id") { value(reservationId) }
        }

        post("/api/v1/reservations/$reservationId/cancel", SeedUsers.student).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("cancelled") }
            jsonPath("$.pickup_deadline") { value(null) }
            jsonPath("$.finished_at") { exists() }
        }
        assertThat(copiesAvailable(bookId)).isEqualTo(copiesBefore)
    }

    @Test
    fun `a user holds one active reservation per book`() {
        val bookId = freeBook()
        val reservationId = reserve(bookId, SeedUsers.student, today().plusDays(1)).idOf()
        try {
            reserve(bookId, SeedUsers.student, today().plusDays(2)).andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("already_reserved") }
            }
        } finally {
            post("/api/v1/reservations/$reservationId/cancel", SeedUsers.student)
        }
    }

    @Test
    fun `the return date must fall within the book's loan`() {
        val bookId = freeBook()
        val maxLoanDays = jdbcClient.sql("select max_reservation_days from books where id = :id")
            .param("id", bookId)
            .query(Int::class.java)
            .single()

        reserve(bookId, SeedUsers.student, today().plusDays(maxLoanDays + 1L)).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_return_date") }
        }
        reserve(bookId, SeedUsers.student, today()).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_return_date") }
        }
    }

    @Test
    fun `the last copy goes to whoever reserves first`() {
        val bookId = freeBook()
        val copiesBefore = copiesAvailable(bookId)
        setCopiesAvailable(bookId, 1)
        try {
            val reservationId = reserve(bookId, SeedUsers.student, today().plusDays(1)).idOf()

            reserve(bookId, SeedUsers.otherStudent, today().plusDays(1)).andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("no_copies_available") }
            }
            get("/api/v1/books/$bookId", SeedUsers.student).andExpect {
                jsonPath("$.available") { value(false) }
            }

            post("/api/v1/reservations/$reservationId/cancel", SeedUsers.student)
        } finally {
            setCopiesAvailable(bookId, copiesBefore)
        }
    }

    @Test
    fun `an admin hands the copy over and takes it back`() {
        val bookId = freeBook()
        val copiesBefore = copiesAvailable(bookId)
        val reservationId = reserve(bookId, SeedUsers.student, today().plusDays(1)).idOf()

        post("/api/v1/reservations/$reservationId/hand-over", SeedUsers.student).andExpect {
            status { isForbidden() }
        }
        post("/api/v1/reservations/$reservationId/hand-over", SeedUsers.admin).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("borrowed") }
            jsonPath("$.borrowed_at") { exists() }
        }
        post("/api/v1/reservations/$reservationId/cancel", SeedUsers.student).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("invalid_status") }
        }
        assertThat(copiesAvailable(bookId)).isEqualTo(copiesBefore - 1)

        post("/api/v1/reservations/$reservationId/return", SeedUsers.admin).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("returned") }
        }
        assertThat(copiesAvailable(bookId)).isEqualTo(copiesBefore)
    }

    @Test
    fun `a borrowed copy past its return date is overdue`() {
        val bookId = freeBook()
        val reservationId = reserve(bookId, SeedUsers.student, today().plusDays(1)).idOf()
        post("/api/v1/reservations/$reservationId/hand-over", SeedUsers.admin)
        try {
            clock.advance(Duration.ofDays(3))

            get("/api/v1/reservations?overdue=true&user_id=${SeedUsers.student.id}", SeedUsers.admin).andExpect {
                status { isOk() }
                jsonPath("$.content[0].id") { value(reservationId) }
                jsonPath("$.content[0].overdue") { value(true) }
            }
        } finally {
            post("/api/v1/reservations/$reservationId/return", SeedUsers.admin)
        }
    }

    @Test
    fun `a copy nobody picks up goes back after the pickup window`() {
        val bookId = freeBook()
        val copiesBefore = copiesAvailable(bookId)
        val reservationId = reserve(bookId, SeedUsers.otherStudent, today().plusDays(3)).idOf()

        clock.advance(Duration.ofHours(47))
        expiryJob.run()
        assertThat(statusOf(reservationId)).isEqualTo("reserved")

        clock.advance(Duration.ofHours(2))
        expiryJob.run()
        assertThat(statusOf(reservationId)).isEqualTo("expired")
        assertThat(copiesAvailable(bookId)).isEqualTo(copiesBefore)
    }

    @Test
    fun `other people's reservations are out of reach`() {
        val bookId = freeBook()
        val reservationId = reserve(bookId, SeedUsers.student, today().plusDays(1)).idOf()
        try {
            post("/api/v1/reservations/$reservationId/cancel", SeedUsers.otherStudent).andExpect {
                status { isNotFound() }
            }
            get("/api/v1/reservations", SeedUsers.student).andExpect {
                status { isForbidden() }
            }
            reserve(bookId, SeedUsers.admin, today().plusDays(1)).andExpect {
                status { isForbidden() }
            }
        } finally {
            post("/api/v1/reservations/$reservationId/cancel", SeedUsers.student)
        }
    }

    @Test
    fun `an unknown status filter is a bad request`() {
        get("/api/v1/reservations/mine?status=lost", SeedUsers.student).andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `the database keeps reservations to their rules`() {
        val bookId = freeBook()
        val tomorrow = "(now() at time zone 'Asia/Yerevan')::date + 1"

        assertThatThrownBy {
            asUser.run(SeedUsers.student.id) { jdbc ->
                jdbc.sql("insert into reservations (user_id, book_id, return_deadline) values (3, :book, $tomorrow)")
                    .param("book", bookId)
                    .update()
            }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)
        assertThatThrownBy {
            asUser.run(SeedUsers.student.id) { jdbc ->
                val id = jdbc.sql(
                    "insert into reservations (user_id, book_id, return_deadline) values (2, :book, $tomorrow) returning id",
                ).param("book", bookId).query(Int::class.java).single()
                jdbc.sql("update reservations set status = 'borrowed' where id = :id").param("id", id).update()
            }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)
        assertThatThrownBy {
            asUser.run(SeedUsers.admin.id) { jdbc ->
                jdbc.sql("delete from reservations where id = 1").update()
            }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)
        assertThatThrownBy {
            asUser.run(SeedUsers.admin.id) { jdbc ->
                jdbc.sql("update reservations set status = 'reserved' where id = 1").update()
            }
        }.hasSqlState(CHECK_VIOLATION)
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

    private fun statusOf(reservationId: Int): String {
        return jdbcClient.sql("select status from reservations where id = :id")
            .param("id", reservationId)
            .query(String::class.java)
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
        returnDeadline: LocalDate,
    ): ResultActionsDsl {
        return mockMvc.post("/api/v1/books/$bookId/reservations") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
            contentType = MediaType.APPLICATION_JSON
            content = """{"return_deadline": "$returnDeadline"}"""
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
