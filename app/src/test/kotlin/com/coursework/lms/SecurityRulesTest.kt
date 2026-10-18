package com.coursework.lms

import com.coursework.lms.support.AsUser
import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.SeedUsers
import org.assertj.core.api.AbstractThrowableAssert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import java.sql.SQLException

/** The database rules V2 puts in place, exercised as the application role with every change rolled back. */
class SecurityRulesTest : IntegrationTest() {

    @Autowired
    private lateinit var asUser: AsUser

    @Autowired
    private lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun `admins manage teacher emails, including deleting them`() {
        val remaining = asUser.run(SeedUsers.admin.id) { jdbc ->
            jdbc.sql("insert into teacher_emails (email) values ('new.teacher@polytechnic.am')").update()
            jdbc.sql("delete from teacher_emails where email = 'new.teacher@polytechnic.am'").update()
            jdbc.sql("select count(*) from teacher_emails where email = 'new.teacher@polytechnic.am'")
                .query(Long::class.java)
                .single()
        }

        assertThat(remaining).isZero()
    }

    @Test
    fun `students cannot manage teacher emails`() {
        assertThatThrownBy {
            asUser.run(SeedUsers.student.id) { jdbc ->
                jdbc.sql("insert into teacher_emails (email) values ('sneaky@polytechnic.am')").update()
            }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)
    }

    @Test
    fun `users change their own username and password`() {
        val username = asUser.run(SeedUsers.student.id) { jdbc ->
            jdbc.sql("update users set username = 'Renamed', password_hash = 'x' where id = :id")
                .param("id", SeedUsers.student.id)
                .update()
            jdbc.sql("select username from users where id = :id")
                .param("id", SeedUsers.student.id)
                .query(String::class.java)
                .single()
        }

        assertThat(username).isEqualTo("Renamed")
    }

    @Test
    fun `users cannot change their own role or email, nor anyone else's account`() {
        val statements = listOf(
            "update users set role_id = 3 where id = 2",
            "update users set email = 'other@polytechnic.am' where id = 2",
            "update users set username = 'Hijacked' where id = 3",
            "delete from users where id = 2",
        )
        statements.forEach { statement ->
            assertThatThrownBy { asUser.run(SeedUsers.student.id) { jdbc -> jdbc.sql(statement).update() } }
                .describedAs(statement)
                .hasSqlState(INSUFFICIENT_PRIVILEGE)
        }
    }

    @Test
    fun `admins change other users' roles`() {
        val roleId = asUser.run(SeedUsers.admin.id) { jdbc ->
            jdbc.sql("update users set role_id = 2 where id = 3").update()
            jdbc.sql("select role_id from users where id = 3").query(Int::class.java).single()
        }

        assertThat(roleId).isEqualTo(2)
    }

    @Test
    fun `signup assigns the role and ignores the one asked for`() {
        val roles = asUser.run(SeedUsers.admin.id) { jdbc ->
            jdbc.sql("insert into teacher_emails (email) values ('signup.teacher@polytechnic.am')").update()
            listOf("signup.student@polytechnic.am", "signup.teacher@polytechnic.am").map { email ->
                jdbc.sql(
                    """
                    insert into users (email, username, password_hash, role_id)
                    values (:email, 'Signup', 'x', 3)
                    returning role_id
                    """.trimIndent(),
                ).param("email", email).query(Int::class.java).single()
            }
        }

        assertThat(roles).containsExactly(1, 2)
    }

    @Test
    fun `signup outside the Polytechnic is rejected`() {
        assertThatThrownBy {
            asUser.run(null) { jdbc ->
                jdbc.sql("insert into users (email, username, password_hash, role_id) values ('a@gmail.com', 'A', 'x', 1)")
                    .update()
            }
        }.hasSqlState(CHECK_VIOLATION)
    }

    @Test
    fun `teachers edit their own books but not copies, loan length or other people's books`() {
        val title = asUser.run(SeedUsers.teacher.id) { jdbc ->
            val ownBook = jdbc.firstBookOf(SeedUsers.teacher.id)
            jdbc.sql("update books set title = 'Edited' where id = :id").param("id", ownBook).update()
            jdbc.sql("select title from books where id = :id").param("id", ownBook).query(String::class.java).single()
        }
        assertThat(title).isEqualTo("Edited")

        listOf("total_copies = total_copies + 1", "copies_available = 0", "max_reservation_days = 99").forEach { change ->
            assertThatThrownBy {
                asUser.run(SeedUsers.teacher.id) { jdbc ->
                    jdbc.sql("update books set $change where id = :id")
                        .param("id", jdbc.firstBookOf(SeedUsers.teacher.id))
                        .update()
                }
            }.describedAs(change).hasSqlState(INSUFFICIENT_PRIVILEGE)
        }

        assertThatThrownBy {
            asUser.run(SeedUsers.teacher.id) { jdbc ->
                jdbc.sql("update books set title = 'Edited' where id = :id")
                    .param("id", jdbc.firstBookOf(SeedUsers.admin.id))
                    .update()
            }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)
    }

    @Test
    fun `teachers delete their own books only while the library holds no copies`() {
        assertThatThrownBy {
            asUser.run(SeedUsers.teacher.id) { jdbc ->
                val bookWithCopies = jdbc.sql("select id from books where added_by = 4 and total_copies > 0 limit 1")
                    .query(Int::class.java)
                    .single()
                jdbc.sql("delete from books where id = :id").param("id", bookWithCopies).update()
            }
        }.hasSqlState(INSUFFICIENT_PRIVILEGE)

        val deleted = asUser.run(SeedUsers.teacher.id) { jdbc ->
            val newBook = jdbc.sql(
                """
                insert into books (title, description, total_copies, copies_available, publisher_id, language_id,
                                   max_reservation_days)
                values ('Draft', 'Draft', 0, 0, 1, 1, 14)
                returning id
                """.trimIndent(),
            ).query(Int::class.java).single()
            jdbc.sql("delete from books where id = :id").param("id", newBook).update()
        }
        assertThat(deleted).isEqualTo(1)
    }

    @Test
    fun `admins change a book's copies`() {
        val copies = asUser.run(SeedUsers.admin.id) { jdbc ->
            val book = jdbc.firstBookOf(SeedUsers.teacher.id)
            jdbc.sql("update books set total_copies = 42 where id = :id").param("id", book).update()
            jdbc.sql("select total_copies from books where id = :id").param("id", book).query(Int::class.java).single()
        }

        assertThat(copies).isEqualTo(42)
    }

    @Test
    fun `students reserve and review books they do not own`() {
        val (copiesBefore, copiesAfter, rating) = asUser.run(SeedUsers.otherStudent.id) { jdbc ->
            val book = jdbc.sql("select id from books where copies_available > 0 order by id limit 1")
                .query(Int::class.java)
                .single()
            val before = jdbc.copiesAvailable(book)
            jdbc.sql("insert into reservations (user_id, book_id, return_deadline) values (3, :book, current_date + 14)")
                .param("book", book)
                .update()
            jdbc.sql("insert into book_reviews (user_id, book_id, stars) values (3, :book, 5)")
                .param("book", book)
                .update()
            val after = jdbc.copiesAvailable(book)
            val rating = jdbc.sql("select rating from books where id = :id")
                .param("id", book)
                .query(Double::class.java)
                .single()
            Triple(before, after, rating)
        }

        assertThat(copiesAfter).isEqualTo(copiesBefore - 1)
        assertThat(rating).isPositive()
    }

    @Test
    fun `a guarded write with nobody set fails as unauthenticated`() {
        assertThatThrownBy {
            asUser.run(null) { jdbc -> jdbc.sql("update books set title = 'Nobody' where id = 1").update() }
        }.hasSqlState(NOT_AUTHENTICATED)
    }

    @Test
    fun `seed passwords are stored as BCrypt hashes of their old values`() {
        val hash = asUser.run(SeedUsers.admin.id) { jdbc ->
            jdbc.sql("select password_hash from users where id = 1").query(String::class.java).single()
        }

        assertThat(passwordEncoder.matches("qwertyuiop", hash)).isTrue()
    }

    private fun JdbcClient.firstBookOf(ownerId: Int): Int {
        return sql("select id from books where added_by = :owner order by id limit 1")
            .param("owner", ownerId)
            .query(Int::class.java)
            .single()
    }

    private fun JdbcClient.copiesAvailable(bookId: Int): Int {
        return sql("select copies_available from books where id = :id")
            .param("id", bookId)
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
        const val CHECK_VIOLATION = "23514"
        const val NOT_AUTHENTICATED = "L0001"
    }
}
