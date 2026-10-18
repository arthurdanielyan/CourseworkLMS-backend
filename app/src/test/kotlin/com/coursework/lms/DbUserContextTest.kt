package com.coursework.lms

import com.coursework.lms.core.db.DbUserContext
import com.coursework.lms.support.IntegrationTest
import com.coursework.lms.support.ProbeService
import com.coursework.lms.support.SeedUsers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionTemplate

class DbUserContextTest : IntegrationTest() {

    @Autowired
    private lateinit var userContext: DbUserContext

    @Autowired
    private lateinit var probe: ProbeService

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Test
    fun `a transaction acts for the user of the access token`() {
        mockMvc.get("/api/v1/test-probe/me") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.student))
        }.andExpect {
            status { isOk() }
            jsonPath("$.user_id") { value(SeedUsers.student.id) }
            jsonPath("$.db_user_id") { value(SeedUsers.student.id.toString()) }
        }
    }

    @Test
    fun `the user does not leak into the next transaction on the same connection`() {
        // The test pool holds a single connection, so both requests run on it.
        mockMvc.get("/api/v1/test-probe/me") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer(SeedUsers.teacher))
        }.andExpect {
            jsonPath("$.db_user_id") { value(SeedUsers.teacher.id.toString()) }
        }

        mockMvc.get("/api/v1/test-probe/public/db-user").andExpect {
            status { isOk() }
            jsonPath("$.db_user_id") { doesNotExist() }
        }
    }

    @Test
    fun `runAs makes transactions act for the given user`() {
        val dbUserId = userContext.runAs(SeedUsers.otherStudent.id) { probe.dbUserId() }

        assertThat(dbUserId).isEqualTo(SeedUsers.otherStudent.id.toString())
        assertThat(probe.dbUserId()).isNull()
    }

    @Test
    fun `runAs refuses to start inside a transaction`() {
        assertThatThrownBy {
            transactionTemplate.execute { userContext.runAs(SeedUsers.admin.id) { probe.dbUserId() } }
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
