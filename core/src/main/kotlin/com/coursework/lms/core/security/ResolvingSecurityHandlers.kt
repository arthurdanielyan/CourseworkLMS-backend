package com.coursework.lms.core.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.web.servlet.HandlerExceptionResolver

/** Answers an unauthenticated request by handing the failure to the MVC exception handler. */
internal class ResolvingAuthenticationEntryPoint(
    private val exceptionResolver: HandlerExceptionResolver,
) : AuthenticationEntryPoint {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        exceptionResolver.resolveException(request, response, null, authException)
    }
}

/** Answers a forbidden request by handing the failure to the MVC exception handler. */
internal class ResolvingAccessDeniedHandler(
    private val exceptionResolver: HandlerExceptionResolver,
) : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        exceptionResolver.resolveException(request, response, null, accessDeniedException)
    }
}
