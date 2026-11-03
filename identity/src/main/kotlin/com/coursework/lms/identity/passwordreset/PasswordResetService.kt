package com.coursework.lms.identity.passwordreset

import com.coursework.lms.core.db.DbUserContext
import com.coursework.lms.core.error.ApiException
import com.coursework.lms.identity.IdentityErrorCode
import com.coursework.lms.identity.IdentityProperties
import com.coursework.lms.identity.db.PasswordResetsTable
import com.coursework.lms.identity.db.UsersTable
import com.coursework.lms.identity.normalizeEmail
import com.coursework.lms.identity.registration.VerificationCodeGenerator
import com.coursework.lms.identity.session.RefreshTokenStore
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Lets a user who forgot their password choose a new one through an emailed code. */
@Service
class PasswordResetService(
    private val properties: IdentityProperties,
    private val passwordEncoder: PasswordEncoder,
    private val codeGenerator: VerificationCodeGenerator,
    private val passwordResetEmail: PasswordResetEmail,
    private val refreshTokenStore: RefreshTokenStore,
    private val dbUserContext: DbUserContext,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock,
) {

    /**
     * Emails a code to the account with [email]. An address without an account gets the same answer and no email,
     * so the answer doesn't tell who has an account.
     */
    @Transactional
    fun start(email: String): PasswordResetSent {
        val address = normalizeEmail(email)
        val now = now()
        val userId = userIdOf(address) ?: return sent(address, now)
        findReset(userId)?.let(::ensureResendAllowed)
        val code = codeGenerator.next()
        PasswordResetsTable.upsert {
            it[PasswordResetsTable.userId] = userId
            it[codeHash] = passwordEncoder.encode(code).orEmpty()
            it[codeExpiresAt] = now.plus(properties.codeTtl)
            it[failedAttempts] = 0
            it[lastSentAt] = now
            it[createdAt] = now
        }
        passwordResetEmail.send(address, code, properties.codeTtl)
        return sent(address, now)
    }

    /** Checks the code without using it up, so the app can move on to choosing the password. */
    fun verify(
        email: String,
        code: String,
    ) {
        withReset(email) { userId -> checkCode(userId, code) }
    }

    /** Sets the new password and logs the user out on every device. */
    fun complete(
        email: String,
        code: String,
        newPassword: String,
    ) {
        withReset(email) { userId ->
            checkCode(userId, code)
            UsersTable.update({ UsersTable.id eq userId }) {
                it[passwordHash] = passwordEncoder.encode(newPassword).orEmpty()
            }
            PasswordResetsTable.deleteWhere { PasswordResetsTable.userId eq userId }
            refreshTokenStore.revokeAllOf(userId)
        }
    }

    // The user is not logged in, yet the database only lets them change their own row, so the work runs as them.
    // A wrong code must stay counted although the request fails, hence the commit before the error is rethrown.
    private fun withReset(
        email: String,
        block: (Int) -> Unit,
    ) {
        val userId = transactionTemplate.execute { userIdOf(normalizeEmail(email)) } ?: throw invalidCode()
        var failure: ApiException? = null
        dbUserContext.runAs(userId) {
            transactionTemplate.executeWithoutResult {
                try {
                    block(userId)
                } catch (error: ApiException) {
                    failure = error
                }
            }
        }
        failure?.let { throw it }
    }

    private fun checkCode(
        userId: Int,
        code: String,
    ) {
        val reset = findReset(userId) ?: throw invalidCode()
        if (reset[PasswordResetsTable.failedAttempts] >= properties.maxCodeAttempts) {
            throw tooManyAttempts()
        }
        if (!reset[PasswordResetsTable.codeExpiresAt].isAfter(now())) {
            throw ApiException(
                status = HttpStatus.BAD_REQUEST,
                code = IdentityErrorCode.CODE_EXPIRED,
                detail = "The code has expired. Request a new one.",
            )
        }
        if (!passwordEncoder.matches(code, reset[PasswordResetsTable.codeHash])) {
            PasswordResetsTable.update({ PasswordResetsTable.userId eq userId }) {
                it[failedAttempts] = failedAttempts + 1
            }
            if (reset[PasswordResetsTable.failedAttempts] + 1 >= properties.maxCodeAttempts) {
                throw tooManyAttempts()
            }
            throw invalidCode()
        }
    }

    private fun userIdOf(address: String): Int? {
        return UsersTable.select(UsersTable.id)
            .where { UsersTable.email eq address }
            .singleOrNull()
            ?.get(UsersTable.id)
    }

    private fun findReset(userId: Int): ResultRow? {
        return PasswordResetsTable.selectAll()
            .where { PasswordResetsTable.userId eq userId }
            .forUpdate()
            .singleOrNull()
    }

    private fun ensureResendAllowed(reset: ResultRow) {
        val wait = Duration.between(now(), reset[PasswordResetsTable.lastSentAt].plus(properties.resendCooldown))
        if (wait > Duration.ZERO) {
            val retryAfterSeconds = (wait.toMillis() + 999) / 1000
            throw ApiException(
                status = HttpStatus.TOO_MANY_REQUESTS,
                code = IdentityErrorCode.TOO_MANY_REQUESTS,
                detail = "A code was sent recently. Try again in $retryAfterSeconds seconds.",
                headers = mapOf(HttpHeaders.RETRY_AFTER to retryAfterSeconds.toString()),
            )
        }
    }

    private fun sent(
        address: String,
        sentAt: OffsetDateTime,
    ): PasswordResetSent {
        return PasswordResetSent(
            email = address,
            codeExpiresAt = sentAt.plus(properties.codeTtl).toInstant(),
            resendAvailableAt = sentAt.plus(properties.resendCooldown).toInstant(),
        )
    }

    private fun invalidCode(): ApiException {
        return ApiException(
            status = HttpStatus.BAD_REQUEST,
            code = IdentityErrorCode.INVALID_CODE,
            detail = "The code is incorrect.",
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
}
