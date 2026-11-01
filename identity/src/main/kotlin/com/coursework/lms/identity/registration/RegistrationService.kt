package com.coursework.lms.identity.registration

import com.coursework.lms.core.error.ApiException
import com.coursework.lms.identity.IdentityErrorCode
import com.coursework.lms.identity.IdentityProperties
import com.coursework.lms.identity.db.PendingRegistrationsTable
import com.coursework.lms.identity.db.RolesTable
import com.coursework.lms.identity.db.UsersTable
import com.coursework.lms.identity.normalizeEmail
import com.coursework.lms.identity.session.TokenPair
import com.coursework.lms.identity.session.TokenPairIssuer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Takes a signup from the form, through its emailed Verification Code, to a logged-in account. */
@Service
class RegistrationService(
    private val properties: IdentityProperties,
    private val passwordEncoder: PasswordEncoder,
    private val codeGenerator: VerificationCodeGenerator,
    private val verificationEmail: VerificationEmail,
    private val tokenPairIssuer: TokenPairIssuer,
    private val clock: Clock,
) {

    // The email goes out inside the transaction: if the mail server refuses it, nothing is stored and the user may
    // retry at once instead of waiting out a cooldown for a code that never arrived.
    @Transactional
    fun start(
        email: String,
        fullName: String,
        password: String,
    ): PendingRegistration {
        val address = normalizeEmail(email)
        ensureNotRegistered(address)
        findPending(address)?.let(::ensureResendAllowed)
        val code = codeGenerator.next()
        val now = now()
        PendingRegistrationsTable.upsert {
            it[PendingRegistrationsTable.email] = address
            it[username] = fullName.trim()
            it[passwordHash] = passwordEncoder.encode(password).orEmpty()
            it[codeHash] = passwordEncoder.encode(code).orEmpty()
            it[codeExpiresAt] = now.plus(properties.codeTtl)
            it[failedAttempts] = 0
            it[lastSentAt] = now
            it[createdAt] = now
        }
        verificationEmail.send(address, code, properties.codeTtl)
        return pendingRegistration(address, now)
    }

    @Transactional
    fun resend(email: String): PendingRegistration {
        val address = normalizeEmail(email)
        val pending = findPending(address) ?: throw registrationNotFound()
        ensureResendAllowed(pending)
        val code = codeGenerator.next()
        val now = now()
        PendingRegistrationsTable.update({ PendingRegistrationsTable.email eq address }) {
            it[codeHash] = passwordEncoder.encode(code).orEmpty()
            it[codeExpiresAt] = now.plus(properties.codeTtl)
            it[failedAttempts] = 0
            it[lastSentAt] = now
        }
        verificationEmail.send(address, code, properties.codeTtl)
        return pendingRegistration(address, now)
    }

    // A wrong code must stay counted although the request fails, or the attempt limit would never be reached.
    @Transactional(noRollbackFor = [ApiException::class])
    fun verify(
        email: String,
        code: String,
    ): TokenPair {
        val address = normalizeEmail(email)
        val pending = findPending(address) ?: throw registrationNotFound()
        if (pending[PendingRegistrationsTable.failedAttempts] >= properties.maxCodeAttempts) {
            throw tooManyAttempts()
        }
        if (!pending[PendingRegistrationsTable.codeExpiresAt].isAfter(now())) {
            throw ApiException(
                status = HttpStatus.BAD_REQUEST,
                code = IdentityErrorCode.CODE_EXPIRED,
                detail = "The verification code has expired. Request a new one.",
            )
        }
        if (!passwordEncoder.matches(code, pending[PendingRegistrationsTable.codeHash])) {
            PendingRegistrationsTable.update({ PendingRegistrationsTable.email eq address }) {
                it[failedAttempts] = failedAttempts + 1
            }
            if (pending[PendingRegistrationsTable.failedAttempts] + 1 >= properties.maxCodeAttempts) {
                throw tooManyAttempts()
            }
            throw ApiException(
                status = HttpStatus.BAD_REQUEST,
                code = IdentityErrorCode.INVALID_CODE,
                detail = "The verification code is incorrect.",
            )
        }
        ensureNotRegistered(address)
        // The signup trigger replaces the role with student or teacher; the column only needs some valid value here.
        val userId = UsersTable.insert {
            it[UsersTable.email] = address
            it[username] = pending[PendingRegistrationsTable.username]
            it[passwordHash] = pending[PendingRegistrationsTable.passwordHash]
            it[roleId] = studentRoleId()
        }[UsersTable.id]
        PendingRegistrationsTable.deleteWhere { PendingRegistrationsTable.email eq address }
        return tokenPairIssuer.issue(userId)
    }

    private fun findPending(address: String): ResultRow? {
        return PendingRegistrationsTable.selectAll()
            .where { PendingRegistrationsTable.email eq address }
            .forUpdate()
            .singleOrNull()
    }

    private fun ensureNotRegistered(address: String) {
        val exists = UsersTable.select(UsersTable.id)
            .where { UsersTable.email eq address }
            .any()
        if (exists) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = IdentityErrorCode.EMAIL_TAKEN,
                detail = "This email is already registered. Log in instead.",
            )
        }
    }

    private fun ensureResendAllowed(pending: ResultRow) {
        val wait = Duration.between(now(), pending[PendingRegistrationsTable.lastSentAt].plus(properties.resendCooldown))
        if (wait > Duration.ZERO) {
            // Rounded up, so a client that waits exactly Retry-After seconds is never turned away again.
            val retryAfterSeconds = (wait.toMillis() + 999) / 1000
            throw ApiException(
                status = HttpStatus.TOO_MANY_REQUESTS,
                code = IdentityErrorCode.TOO_MANY_REQUESTS,
                detail = "A code was sent recently. Try again in $retryAfterSeconds seconds.",
                headers = mapOf(HttpHeaders.RETRY_AFTER to retryAfterSeconds.toString()),
            )
        }
    }

    private fun studentRoleId(): Int {
        return RolesTable.select(RolesTable.id)
            .where { RolesTable.name eq STUDENT_ROLE }
            .single()[RolesTable.id]
    }

    private fun pendingRegistration(
        address: String,
        sentAt: OffsetDateTime,
    ): PendingRegistration {
        return PendingRegistration(
            email = address,
            codeExpiresAt = sentAt.plus(properties.codeTtl).toInstant(),
            resendAvailableAt = sentAt.plus(properties.resendCooldown).toInstant(),
        )
    }

    private fun registrationNotFound(): ApiException {
        return ApiException(
            status = HttpStatus.NOT_FOUND,
            code = IdentityErrorCode.REGISTRATION_NOT_FOUND,
            detail = "There is no signup waiting for this email. Start the signup again.",
        )
    }

    private fun tooManyAttempts(): ApiException {
        return ApiException(
            status = HttpStatus.BAD_REQUEST,
            code = IdentityErrorCode.TOO_MANY_ATTEMPTS,
            detail = "Too many incorrect codes. Request a new one.",
        )
    }

    private fun now(): OffsetDateTime {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
    }

    private companion object {
        const val STUDENT_ROLE = "student"
    }
}
