package com.coursework.lms.notifications

import com.coursework.lms.circulation.LIBRARY_ZONE
import com.coursework.lms.circulation.reservation.ReservationExpiryJob
import com.coursework.lms.core.db.DbUserContext
import com.coursework.lms.core.testing.assertSchemaMatches
import com.coursework.lms.notifications.db.NotificationsTable
import com.coursework.lms.support.AsUser
import com.coursework.lms.support.AuthFlows
import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.MutableClock
import com.coursework.lms.support.RecordingEmailSender
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

// Each test uses freshly registered users, so their inboxes hold only what the test causes.
class NotificationsTest : IntegrationTest() {

    @Autowired
    private lateinit var auth: AuthFlows

    @Autowired
    private lateinit var emails: RecordingEmailSender

    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var notificationJob: NotificationJob

    @Autowired
    private lateinit var reservationExpiryJob: ReservationExpiryJob

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var asUser: AsUser

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
    fun `the notifications table matches the migrated schema`() {
        assertSchemaMatches(NotificationsTable)
    }

    @Test
    fun `a waitlist offer reaches the inbox and the mailbox once`() {
        val bookId = freeBook()
        val copiesBefore = copiesAvailable(bookId)
        setCopiesAvailable(bookId, 1)
        val holder = auth.register()
        val waiterEmail = auth.newEmail()
        val waiter = auth.register(waiterEmail)
        try {
            val reservationId = reserve(bookId, holder.bearer).idOf()
            post("/api/v1/books/$bookId/waitlist", waiter.bearer).andExpect { status { isCreated() } }
            post("/api/v1/reservations/$reservationId/cancel", holder.bearer)

            notificationJob.run()
            notificationJob.run()

            get("/api/v1/notifications", waiter.bearer).andExpect {
                status { isOk() }
                jsonPath("$.page.total_elements") { value(1) }
                jsonPath("$.content[0].type") { value("waitlist_offer") }
                jsonPath("$.content[0].book.id") { value(bookId) }
                jsonPath("$.content[0].read") { value(false) }
            }
            assertThat(emails.sentTo(waiterEmail).filter { it.subject.contains("is waiting for you") }).hasSize(1)
            get("/api/v1/notifications/unread-count", waiter.bearer).andExpect { jsonPath("$.count") { value(1) } }

            post("/api/v1/notifications/read-all", waiter.bearer).andExpect { status { isNoContent() } }
            get("/api/v1/notifications/unread-count", waiter.bearer).andExpect { jsonPath("$.count") { value(0) } }
            get("/api/v1/notifications?unread_only=true", waiter.bearer).andExpect {
                jsonPath("$.page.total_elements") { value(0) }
            }
        } finally {
            mockMvc.delete("/api/v1/books/$bookId/waitlist") { header(HttpHeaders.AUTHORIZATION, waiter.bearer) }
            setCopiesAvailable(bookId, copiesBefore)
        }
    }

    @Test
    fun `an uncollected reservation is reminded about, then reported expired`() {
        val user = auth.register()
        val bookId = freeBook()
        reserve(bookId, user.bearer).andExpect { status { isCreated() } }

        clock.advance(Duration.ofHours(35))
        notificationJob.run()
        assertThat(types(user.bearer)).doesNotContain("pickup_reminder")

        clock.advance(Duration.ofHours(2))
        notificationJob.run()
        assertThat(types(user.bearer)).containsExactly("pickup_reminder")

        clock.advance(Duration.ofHours(12))
        reservationExpiryJob.run()
        notificationJob.run()
        assertThat(types(user.bearer)).containsExactly("reservation_expired", "pickup_reminder")
    }

    @Test
    fun `a borrowed copy is reminded about the day before and reported overdue`() {
        val user = auth.register()
        val bookId = freeBook()
        val reservationId = reserve(bookId, user.bearer).idOf()
        post("/api/v1/reservations/$reservationId/hand-over", tokens.bearer(SeedUsers.admin))
        try {
            notificationJob.run()
            assertThat(types(user.bearer)).containsExactly("return_reminder")

            clock.advance(Duration.ofDays(2))
            notificationJob.run()
            assertThat(types(user.bearer)).containsExactly("overdue", "return_reminder")
        } finally {
            post("/api/v1/reservations/$reservationId/return", tokens.bearer(SeedUsers.admin))
        }
    }

    @Test
    fun `other people's notifications are out of reach`() {
        val owner = auth.register()
        val stranger = auth.register()
        val bookId = freeBook()
        val reservationId = reserve(bookId, owner.bearer).idOf()
        post("/api/v1/reservations/$reservationId/hand-over", tokens.bearer(SeedUsers.admin))
        try {
            notificationJob.run()
            val notificationId = json.readTree(
                get("/api/v1/notifications", owner.bearer).andReturn().response.contentAsString,
            )["content"][0]["id"].asInt()

            post("/api/v1/notifications/$notificationId/read", stranger.bearer).andExpect { status { isNotFound() } }
            assertThatThrownBy {
                asUser.run(SeedUsers.student.id) { jdbc ->
                    jdbc.sql("update notifications set is_read = true where id = :id").param("id", notificationId).update()
                }
            }.hasSqlState(INSUFFICIENT_PRIVILEGE)
            assertThatThrownBy {
                asUser.run(SeedUsers.student.id) { jdbc ->
                    jdbc.sql("update notifications set title = 'Changed' where id = :id").param("id", notificationId).update()
                }
            }.hasSqlState(INSUFFICIENT_PRIVILEGE)
            post("/api/v1/notifications/$notificationId/read", owner.bearer).andExpect { status { isNoContent() } }
        } finally {
            post("/api/v1/reservations/$reservationId/return", tokens.bearer(SeedUsers.admin))
        }
    }

    private fun types(bearer: String): List<String> {
        val body = json.readTree(get("/api/v1/notifications", bearer).andReturn().response.contentAsString)
        val content = body["content"]
        return (0 until content.size()).map { content[it]["type"].asString() }
    }

    private fun freeBook(): Int {
        return jdbcClient.sql(
            """
            select id from books b
            where copies_available > 1 and deleted_at is null
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
        bearer: String,
    ): ResultActionsDsl {
        val tomorrow = LocalDate.ofInstant(clock.instant(), LIBRARY_ZONE).plusDays(1)
        return mockMvc.post("/api/v1/books/$bookId/reservations") {
            header(HttpHeaders.AUTHORIZATION, bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"return_deadline": "$tomorrow"}"""
        }
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
    ): ResultActionsDsl {
        return mockMvc.post(path) { header(HttpHeaders.AUTHORIZATION, bearer) }
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
