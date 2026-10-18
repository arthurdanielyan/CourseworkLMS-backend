package com.coursework.lms.core.testing

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils

/**
 * Asserts that [tables] describe the Flyway-built schema exactly, so a module's Exposed mapping cannot drift from its
 * migrations. Call it from an integration test whose application context has already run Flyway on [LmsPostgres].
 */
fun assertSchemaMatches(vararg tables: Table) {
    val database = Database.connect(
        url = LmsPostgres.container.jdbcUrl,
        user = LmsPostgres.container.username,
        password = LmsPostgres.container.password,
    )
    val statements = transaction(database) {
        MigrationUtils.statementsRequiredForDatabaseMigration(*tables, withLogs = false)
    }
    assertThat(statements)
        .describedAs("statements needed to make the database match the Exposed tables")
        .isEmpty()
}
