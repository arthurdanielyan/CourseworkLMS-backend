package com.coursework.lms

import com.coursework.lms.core.testing.LmsPostgres
import com.coursework.lms.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.DriverManager
import java.sql.SQLException

class MigrationsTest : IntegrationTest() {

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Test
    fun `baseline and security migrations are applied`() {
        val container = LmsPostgres.container
        val versions = DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use {
            it.createStatement()
                .executeQuery("select version from flyway_schema_history where success order by installed_rank")
                .let { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
        }

        assertThat(versions).containsExactly("1", "2")
    }

    @Test
    fun `the application role cannot read the migration history`() {
        assertThatThrownBy { jdbcClient.sql("select count(*) from flyway_schema_history").query().singleValue() }
            .isInstanceOf(DataAccessException::class.java)
            .rootCause()
            .hasMessageContaining("permission denied")
    }

    @Test
    fun `the application connects as a non-superuser`() {
        val row = jdbcClient.sql("select current_user as name, rolsuper from pg_roles where rolname = current_user")
            .query()
            .singleRow()

        assertThat(row["name"]).isEqualTo("lms_app")
        assertThat(row["rolsuper"]).isEqualTo(false)
    }

    @Test
    fun `the application role cannot switch triggers off`() {
        assertThatThrownBy { jdbcClient.sql("alter table books disable trigger all").update() }
            .isInstanceOf(DataAccessException::class.java)
            .rootCause()
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("must be owner")
        assertThatThrownBy { jdbcClient.sql("set session_replication_role = replica").update() }
            .isInstanceOf(DataAccessException::class.java)
            .rootCause()
            .hasMessageContaining("permission denied")
    }
}
