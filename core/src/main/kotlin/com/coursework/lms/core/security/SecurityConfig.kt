package com.coursework.lms.core.security

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.servlet.HandlerExceptionResolver

@Configuration
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    fun coreInfrastructureEndpoints(): PublicEndpoints {
        return PublicEndpoints(
            patterns = listOf(
                "/actuator/health",
                "/v3/api-docs/**",
                "/swagger-ui.html",
                "/swagger-ui/**",
                "/error",
            ),
        )
    }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        publicEndpoints: List<PublicEndpoints>,
        authenticationConverter: CurrentUserAuthenticationConverter,
        @Qualifier("handlerExceptionResolver") exceptionResolver: HandlerExceptionResolver,
    ): SecurityFilterChain {
        // Security failures go through the MVC exception handler, so they share one problem-detail format.
        val entryPoint = ResolvingAuthenticationEntryPoint(exceptionResolver)
        val accessDeniedHandler = ResolvingAccessDeniedHandler(exceptionResolver)
        http {
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            logout { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                publicEndpoints.flatMap { it.patterns }.forEach { pattern -> authorize(pattern, permitAll) }
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer {
                jwt { jwtAuthenticationConverter = authenticationConverter }
                authenticationEntryPoint = entryPoint
                this.accessDeniedHandler = accessDeniedHandler
            }
            exceptionHandling {
                authenticationEntryPoint = entryPoint
                this.accessDeniedHandler = accessDeniedHandler
            }
        }
        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder()
    }
}
