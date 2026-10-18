package com.coursework.lms.core.testing

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * One PostgreSQL 18 shared by every integration test in the JVM, set up the way a real server is: the `lms_app`
 * role exists with a known password before Flyway runs.
 */
object LmsPostgres {

    const val APP_USER = "lms_app"
    const val APP_PASSWORD = "lms_app"

    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:18")
            .withDatabaseName("LMS")
            .withInitScript("db/init/app-role.sql")
            .apply { start() }
    }

    /** Points the application at [container]: the pool as `lms_app`, Flyway as the container's superuser. */
    fun registerProperties(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url") { container.jdbcUrl }
        registry.add("spring.datasource.username") { APP_USER }
        registry.add("spring.datasource.password") { APP_PASSWORD }
        registry.add("spring.flyway.user") { container.username }
        registry.add("spring.flyway.password") { container.password }
    }
}
