package com.coursework.lms.identity.session

import com.coursework.lms.core.error.ApiException
import com.coursework.lms.identity.IdentityErrorCode
import com.coursework.lms.identity.db.UsersTable
import com.coursework.lms.identity.normalizeEmail
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime

/** Logs users in with their password and renews their sessions with refresh tokens. */
@Service
class SessionService(
    private val passwordEncoder: PasswordEncoder,
    private val tokenPairIssuer: TokenPairIssuer,
    private val refreshTokens: RefreshTokenStore,
    private val clock: Clock,
) {

    // Compared against when the email is unknown, so a missing account answers as slowly as a wrong password.
    private val dummyPasswordHash by lazy { passwordEncoder.encode("not-a-real-password").orEmpty() }

    @Transactional
    fun login(
        email: String,
        password: String,
    ): TokenPair {
        val user = UsersTable.select(UsersTable.id, UsersTable.passwordHash)
            .where { UsersTable.email eq normalizeEmail(email) }
            .singleOrNull()
        val passwordHash = user?.get(UsersTable.passwordHash) ?: dummyPasswordHash
        if (!passwordEncoder.matches(password, passwordHash) || user == null) {
            throw ApiException(
                status = HttpStatus.BAD_REQUEST,
                code = IdentityErrorCode.INVALID_CREDENTIALS,
                detail = "The email or password is incorrect.",
            )
        }
        return tokenPairIssuer.issue(user[UsersTable.id])
    }

    // Revoking every session on reuse has to survive the failure it reports.
    @Transactional(noRollbackFor = [ApiException::class])
    fun refresh(refreshToken: String): TokenPair {
        val stored = refreshTokens.findForUpdate(refreshToken) ?: throw invalidRefreshToken()
        if (stored.isRevoked) {
            // A rotated token coming back means someone else holds a copy, so every session of the user ends.
            refreshTokens.revokeAllOf(stored.userId)
            throw invalidRefreshToken()
        }
        if (!stored.expiresAt.isAfter(OffsetDateTime.now(clock))) {
            throw invalidRefreshToken()
        }
        val replacement = refreshTokens.create(stored.userId)
        refreshTokens.markReplaced(stored.id, replacement.id)
        return tokenPairIssuer.issue(stored.userId, replacement)
    }

    private fun invalidRefreshToken(): ApiException {
        return ApiException(
            status = HttpStatus.BAD_REQUEST,
            code = IdentityErrorCode.INVALID_REFRESH_TOKEN,
            detail = "The refresh token is invalid or expired. Log in again.",
        )
    }
}
