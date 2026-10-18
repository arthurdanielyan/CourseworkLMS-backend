package com.coursework.lms.core.error

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException

/** Renders every failure as an RFC 9457 problem response carrying a `code` from [ErrorCode]. */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    private val wordBoundary = Regex("([a-z0-9])([A-Z])")

    @ExceptionHandler(ApiException::class)
    fun handleApiException(error: ApiException): ResponseEntity<ProblemDetail> {
        return problem(error.status, error.code, error.detail)
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthentication(error: AuthenticationException): ResponseEntity<ProblemDetail> {
        // Only a missing or rejected token may answer 401: the client treats every 401 as "refresh the token".
        val oauthError = (error as? OAuth2AuthenticationException)?.error
        val challenge = if (oauthError == null) {
            "Bearer"
        } else {
            "Bearer error=\"${oauthError.errorCode}\", error_description=\"${oauthError.description.orEmpty()}\""
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .header(HttpHeaders.WWW_AUTHENTICATE, challenge)
            .body(problemDetail(HttpStatus.UNAUTHORIZED, ErrorCode.NOT_AUTHENTICATED, "A valid access token is required."))
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(error: AccessDeniedException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, "You are not allowed to do this.")
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(error: Exception): ResponseEntity<ProblemDetail> {
        val databaseError = error.findDatabaseError() ?: return internalError(error)
        return when (databaseError.sqlState) {
            SqlState.INSUFFICIENT_PRIVILEGE -> problem(HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, databaseError.message)
            SqlState.UNIQUE_VIOLATION,
            SqlState.FOREIGN_KEY_VIOLATION -> problem(HttpStatus.CONFLICT, ErrorCode.CONFLICT, databaseError.message)
            SqlState.CHECK_VIOLATION -> problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, databaseError.message)
            // A trigger found no user: the backend forgot to set one, which is a server bug, never a 401 (ADR 0001).
            SqlState.NOT_AUTHENTICATED -> internalError(error)
            else -> internalError(error)
        }
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val fieldErrors = ex.bindingResult.fieldErrors.map { error ->
            FieldViolation(
                field = jsonName(error.field),
                message = error.defaultMessage.orEmpty(),
            )
        }
        return validationFailed(fieldErrors)
    }

    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val fieldErrors = ex.parameterValidationResults.flatMap { result ->
            result.resolvableErrors.map { error ->
                FieldViolation(
                    field = jsonName(result.methodParameter.parameterName.orEmpty()),
                    message = error.defaultMessage.orEmpty(),
                )
            }
        }
        return validationFailed(fieldErrors)
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        // Framework-raised failures (unknown path, bad JSON, wrong method, ...) get a code like every other problem.
        val problem = (body as? ProblemDetail) ?: ProblemDetail.forStatus(statusCode)
        problem.setProperty(CODE_PROPERTY, codeFor(ex, statusCode))
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request)
    }

    private fun codeFor(
        error: Exception,
        status: HttpStatusCode,
    ): String {
        return when {
            error is NoResourceFoundException -> ErrorCode.NOT_FOUND
            error is HttpRequestMethodNotSupportedException -> ErrorCode.METHOD_NOT_ALLOWED
            status.value() == HttpStatus.NOT_FOUND.value() -> ErrorCode.NOT_FOUND
            status.is4xxClientError -> ErrorCode.BAD_REQUEST
            else -> ErrorCode.INTERNAL_ERROR
        }
    }

    // Fields are reported by the names the client sends, not by the Kotlin property names.
    private fun jsonName(propertyPath: String): String {
        return propertyPath.replace(wordBoundary, "$1_$2").lowercase()
    }

    private fun validationFailed(fieldErrors: List<FieldViolation>): ResponseEntity<Any> {
        val problem = problemDetail(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "The request is invalid.")
        problem.setProperty(ERRORS_PROPERTY, fieldErrors)
        return ResponseEntity.badRequest().body(problem)
    }

    private fun internalError(error: Exception): ResponseEntity<ProblemDetail> {
        log.error("Unhandled failure", error)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Something went wrong.")
    }

    private fun problem(
        status: HttpStatus,
        code: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        return ResponseEntity.status(status).body(problemDetail(status, code, detail))
    }

    private fun problemDetail(
        status: HttpStatus,
        code: String,
        detail: String,
    ): ProblemDetail {
        return ProblemDetail.forStatusAndDetail(status, detail).apply {
            setProperty(CODE_PROPERTY, code)
        }
    }

    /** One rejected request field and why it was rejected. */
    data class FieldViolation(
        val field: String,
        val message: String,
    )

    private companion object {
        const val CODE_PROPERTY = "code"
        const val ERRORS_PROPERTY = "errors"
    }
}
