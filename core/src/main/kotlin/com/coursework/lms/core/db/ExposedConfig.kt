package com.coursework.lms.core.db

import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.ExposedSpringTransactionAttributeSource
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Role
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

// Replaces exposed-spring-boot4-starter's auto-configuration: its SpringTransactionManager is final and registered
// unconditionally, so it is wrapped here instead of being extended.
@Configuration
class ExposedConfig {

    @Bean
    fun databaseConfig(): DatabaseConfig {
        return DatabaseConfig {}
    }

    @Bean
    fun transactionManager(
        dataSource: DataSource,
        databaseConfig: DatabaseConfig,
        userContext: DbUserContext,
        jdbcClient: ObjectProvider<JdbcClient>,
        @Value("\${lms.exposed.show-sql:false}") showSql: Boolean,
    ): PlatformTransactionManager {
        return UserContextTransactionManager(
            delegate = SpringTransactionManager(dataSource, databaseConfig, showSql),
            userContext = userContext,
            jdbcClient = jdbcClient,
        )
    }

    // ExposedSQLException is a checked SQLException, which @Transactional would otherwise commit on.
    @Bean
    @Primary
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun exposedSpringTransactionAttributeSource(): ExposedSpringTransactionAttributeSource {
        return ExposedSpringTransactionAttributeSource()
    }
}
