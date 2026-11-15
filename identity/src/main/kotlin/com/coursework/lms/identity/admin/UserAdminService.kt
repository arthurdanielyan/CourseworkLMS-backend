package com.coursework.lms.identity.admin

import com.coursework.lms.core.error.ApiException
import com.coursework.lms.core.error.ErrorCode
import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import com.coursework.lms.core.paging.toPage
import com.coursework.lms.identity.IdentityErrorCode
import com.coursework.lms.identity.db.RolesTable
import com.coursework.lms.identity.db.UsersTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.compoundAnd
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

data class UserSummary(
    val id: Int,
    val email: String,
    val fullName: String,
    val role: String,
    val registeredAt: OffsetDateTime,
)

/** Lets admins find users, change their Role and delete their accounts. */
@Service
class UserAdminService(
    private val deletionHooks: List<UserDeletionHook>,
) {

    /** Users whose name or email contains [text], optionally of one [role], sorted by name. */
    @Transactional(readOnly = true)
    fun list(
        text: String?,
        role: String?,
        pageQuery: PageQuery,
    ): PageResponse<UserSummary> {
        val criteria = buildList<Op<Boolean>> {
            text?.let {
                val literal = LikePattern.ofLiteral(it.lowercase())
                val pattern = LikePattern("%${literal.pattern}%", literal.escapeChar)
                add((UsersTable.username.lowerCase() like pattern) or (UsersTable.email like pattern))
            }
            role?.let { add(RolesTable.name eq it) }
        }
        return userQuery()
            .where { if (criteria.isEmpty()) Op.TRUE else criteria.compoundAnd() }
            .orderBy(UsersTable.username to SortOrder.ASC, UsersTable.id to SortOrder.ASC)
            .toPage(pageQuery, ::toSummary)
    }

    @Transactional
    fun changeRole(
        userId: Int,
        role: String,
        adminId: Int,
    ): UserSummary {
        val user = find(userId)
        refuseOwnAccount(userId, adminId)
        val roleId = RolesTable.select(RolesTable.id).where { RolesTable.name eq role }.singleOrNull()?.get(RolesTable.id)
            ?: throw ApiException(
                status = HttpStatus.BAD_REQUEST,
                code = ErrorCode.BAD_REQUEST,
                detail = "There is no role '$role'.",
            )
        if (role != ADMIN) {
            refuseLastAdmin(user)
        }
        UsersTable.update({ UsersTable.id eq userId }) { it[UsersTable.roleId] = roleId }
        return find(userId)
    }

    /** Deletes an account: its Reviews stay without an author, its books become University Books, the rest goes. */
    @Transactional
    fun delete(
        userId: Int,
        adminId: Int,
    ) {
        val user = find(userId)
        refuseOwnAccount(userId, adminId)
        refuseLastAdmin(user)
        deletionHooks.forEach { it.beforeDelete(userId) }
        UsersTable.deleteWhere { UsersTable.id eq userId }
    }

    private fun find(userId: Int): UserSummary {
        return userQuery()
            .where { UsersTable.id eq userId }
            .singleOrNull()
            ?.let(::toSummary)
            ?: throw ApiException(
                status = HttpStatus.NOT_FOUND,
                code = ErrorCode.NOT_FOUND,
                detail = "There is no user with id $userId.",
            )
    }

    // An admin locking themselves out, or the library losing its last admin, can't be undone through the API.
    private fun refuseOwnAccount(
        userId: Int,
        adminId: Int,
    ) {
        if (userId == adminId) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = IdentityErrorCode.OWN_ACCOUNT,
                detail = "Admins can't change the role of, or delete, their own account.",
            )
        }
    }

    private fun refuseLastAdmin(user: UserSummary) {
        if (user.role != ADMIN) {
            return
        }
        val admins = userQuery().where { RolesTable.name eq ADMIN }.count()
        if (admins <= 1) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = IdentityErrorCode.LAST_ADMIN,
                detail = "The library must keep at least one admin.",
            )
        }
    }

    private fun userQuery(): Query {
        return UsersTable
            .join(RolesTable, JoinType.INNER, UsersTable.roleId, RolesTable.id)
            .select(UsersTable.id, UsersTable.email, UsersTable.username, UsersTable.registeredAt, RolesTable.name)
    }

    private fun toSummary(row: ResultRow): UserSummary {
        return UserSummary(
            id = row[UsersTable.id],
            email = row[UsersTable.email],
            fullName = row[UsersTable.username],
            role = row[RolesTable.name],
            registeredAt = row[UsersTable.registeredAt],
        )
    }

    private companion object {
        const val ADMIN = "admin"
    }
}
