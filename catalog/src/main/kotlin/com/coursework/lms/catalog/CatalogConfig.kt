package com.coursework.lms.catalog

import com.coursework.lms.core.security.PublicEndpoints
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CatalogConfig {

    // Covers load in image views and PDFs in download managers, neither of which sends the access token. The ids
    // are random UUIDs, so a file is reachable only through a book that links it.
    @Bean
    fun catalogPublicEndpoints(): PublicEndpoints {
        return PublicEndpoints(patterns = listOf("/api/v1/files/*"))
    }
}
