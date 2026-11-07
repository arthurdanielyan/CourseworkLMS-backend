package com.coursework.lms.identity.account

import com.coursework.lms.core.error.ApiException
import com.coursework.lms.identity.IdentityErrorCode
import com.coursework.lms.identity.db.UsersTable
import com.coursework.lms.identity.session.CurrentUserLoader
import com.coursework.lms.identity.session.RefreshTokenStore
import com.coursework.lms.identity.session.TokenPair
import com.coursework.lms.identity.session.TokenPairIssuer
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The logged-in user's own account. */
@Service
class AccountService(
    private val currentUserLoader: CurrentUserLoader,
    private val passwordEncoder: PasswordEncoder,
    private val refreshTokens: RefreshTokenStore,
    private val tokenPairIssuer: TokenPairIssuer,
) {

    // Read from the database rather than the token, so a role changed since the last refresh shows at once.
    @Transactional(readOnly = true)
    fun find(userId: Int): Account {
        val user = UsersTable.select(UsersTable.email, UsersTable.username)
            .where { UsersTable.id eq userId }
            .single()
        val access = currentUserLoader.load(userId)
        return Account(
            id = userId,
            email = user[UsersTable.email],
            fullName = user[UsersTable.username],
            role = access.role,
            permissions = access.permissions,
        )
    }

    @Transactional
    fun rename(
        userId: Int,
        fullName: String,
    ): Account {
        UsersTable.update({ UsersTable.id eq userId }) { it[username] = fullName }
        return find(userId)
    }

    /** Sets a new password, ends every session, and starts a new one for the device that asked. */
    @Transactional
    fun changePassword(
        userId: Int,
        currentPassword: String,
        newPassword: String,
    ): TokenPair {
        val hash = UsersTable.select(UsersTable.passwordHash)
            .where { UsersTable.id eq userId }
            .single()[UsersTable.passwordHash]
        if (!passwordEncoder.matches(currentPassword, hash)) {
            throw ApiException(
                status = HttpStatus.BAD_REQUEST,
                code = IdentityErrorCode.INVALID_CREDENTIALS,
                detail = "The current password is incorrect.",
            )
        }
        UsersTable.update({ UsersTable.id eq userId }) {
            it[passwordHash] = passwordEncoder.encode(newPassword).orEmpty()
        }
        refreshTokens.revokeAllOf(userId)
        return tokenPairIssuer.issue(userId)
    }
}
