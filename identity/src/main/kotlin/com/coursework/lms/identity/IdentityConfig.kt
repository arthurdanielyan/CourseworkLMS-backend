package com.coursework.lms.identity

import com.coursework.lms.core.security.PublicEndpoints
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(IdentityProperties::class)
class IdentityConfig {

    @Bean
    fun identityPublicEndpoints(): PublicEndpoints {
        return PublicEndpoints(patterns = listOf("/api/v1/auth/**"))
    }
}
