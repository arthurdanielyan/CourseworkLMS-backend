package com.coursework.lms.openapi

import com.coursework.lms.core.security.PublicEndpoints
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import io.swagger.v3.core.jackson.ModelResolver
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser

@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI {
        return OpenAPI()
            .info(Info().title("Library Management System API").version("v1"))
            .components(
                Components().addSecuritySchemes(
                    BEARER_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"),
                ),
            )
            .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))
    }

    @Bean
    fun snakeCaseModelResolver(): ModelResolver {
        // springdoc describes schemas with its own Jackson 2 mapper, which never sees the app's Jackson 3 snake_case setting.
        val mapper = Json.mapper().copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        return ModelResolver(mapper)
    }

    @Bean
    fun publicEndpointsNeedNoToken(publicEndpoints: List<PublicEndpoints>): OpenApiCustomizer {
        val patterns = publicEndpoints.flatMap { it.patterns }.map { PathPatternParser.defaultInstance.parse(it) }
        return OpenApiCustomizer { openApi ->
            openApi.paths.orEmpty().forEach { (path, item) ->
                if (patterns.any { it.matches(PathContainer.parsePath(path)) }) {
                    // An empty list overrides the document-wide bearer requirement for this operation.
                    item.readOperations().forEach { it.security = emptyList() }
                }
            }
        }
    }

    private companion object {
        const val BEARER_SCHEME = "bearer"
    }
}
