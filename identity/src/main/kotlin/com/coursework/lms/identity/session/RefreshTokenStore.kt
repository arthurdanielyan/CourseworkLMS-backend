package com.coursework.lms.identity.session

import com.coursework.lms.identity.IdentityProperties
import com.coursework.lms.identity.db.RefreshTokensTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat

/** A refresh token just stored: its [id] row and the raw [value] handed to the client, never stored itself. */
data class NewRefreshToken(
    val id: Long,
    val value: String,
)

/** A stored refresh token, found by the raw value a client presented. */
data class StoredRefreshToken(
    val id: Long,
    val userId: Int,
    val expiresAt: OffsetDateTime,
    val isRevoked: Boolean,
)

/** Stores refresh tokens by their SHA-256 hash and tracks their rotation. All functions need a transaction. */
@Component
class RefreshTokenStore(
    private val properties: IdentityProperties,
    private val clock: Clock,
) {

    private val random = SecureRandom()

    fun create(userId: Int): NewRefreshToken {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val now = now()
        val id = RefreshTokensTable.insert {
            it[RefreshTokensTable.userId] = userId
            it[tokenHash] = hash(value)
            it[expiresAt] = now.plus(properties.refreshTokenTtl)
            it[createdAt] = now
        }[RefreshTokensTable.id]
        return NewRefreshToken(
            id = id,
            value = value,
        )
    }

    /** The token [value] names, locked until the transaction ends so two refreshes cannot both rotate it. */
    fun findForUpdate(value: String): StoredRefreshToken? {
        return RefreshTokensTable.selectAll()
            .where { RefreshTokensTable.tokenHash eq hash(value) }
            .forUpdate()
            .singleOrNull()
            ?.toStoredRefreshToken()
    }

    fun markReplaced(
        id: Long,
        replacementId: Long,
    ) {
        RefreshTokensTable.update({ RefreshTokensTable.id eq id }) {
            it[revokedAt] = now()
            it[replacedById] = replacementId
        }
    }

    fun revokeAllOf(userId: Int) {
        RefreshTokensTable.update({ (RefreshTokensTable.userId eq userId) and RefreshTokensTable.revokedAt.isNull() }) {
            it[revokedAt] = now()
        }
    }

    private fun now(): OffsetDateTime {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
    }

    // A random 256-bit token needs no slow hash: there is nothing to guess, only a leaked table to protect against.
    private fun hash(value: String): String {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    }

    private fun ResultRow.toStoredRefreshToken(): StoredRefreshToken {
        return StoredRefreshToken(
            id = this[RefreshTokensTable.id],
            userId = this[RefreshTokensTable.userId],
            expiresAt = this[RefreshTokensTable.expiresAt],
            isRevoked = this[RefreshTokensTable.revokedAt] != null,
        )
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
