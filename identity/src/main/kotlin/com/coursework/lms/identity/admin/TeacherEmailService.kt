package com.coursework.lms.identity.admin

import com.coursework.lms.core.error.ApiException
import com.coursework.lms.core.error.ErrorCode
import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import com.coursework.lms.core.paging.toPage
import com.coursework.lms.identity.IdentityErrorCode
import com.coursework.lms.identity.db.RolesTable
import com.coursework.lms.identity.db.TeacherEmailsTable
import com.coursework.lms.identity.db.UsersTable
import com.coursework.lms.identity.normalizeEmail
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A Teacher Email, with the account registered under it if there is one. */
data class TeacherEmail(
    val id: Int,
    val email: String,
    val account: UserSummary?,
)

/** Keeps the list of Teacher Emails, and the role of accounts already registered under them in step with it. */
@Service
class TeacherEmailService {

    /** The Teacher Emails, sorted by address. */
    @Transactional(readOnly = true)
    fun list(pageQuery: PageQuery): PageResponse<TeacherEmail> {
        return entryQuery()
            .orderBy(TeacherEmailsTable.email to SortOrder.ASC)
            .toPage(pageQuery, ::toTeacherEmail)
    }

    /** Adds [email]; a Student already registered under it becomes a Teacher. */
    @Transactional
    fun add(email: String): TeacherEmail {
        val address = normalizeEmail(email)
        val listed = TeacherEmailsTable.select(TeacherEmailsTable.id).where { TeacherEmailsTable.email eq address }.any()
        if (listed) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = IdentityErrorCode.ALREADY_LISTED,
                detail = "$address is already a Teacher Email.",
            )
        }
        val id = TeacherEmailsTable.insert { it[TeacherEmailsTable.email] = address }[TeacherEmailsTable.id]
        moveRole(address, from = STUDENT, to = TEACHER)
        return find(id)
    }

    /** Removes a Teacher Email; a Teacher registered under it becomes a Student. */
    @Transactional
    fun remove(id: Int) {
        val entry = find(id)
        TeacherEmailsTable.deleteWhere { TeacherEmailsTable.id eq id }
        moveRole(entry.email, from = TEACHER, to = STUDENT)
    }

    // An Admin's role never follows the list: it changes only through an explicit role change.
    private fun moveRole(
        address: String,
        from: String,
        to: String,
    ) {
        val fromId = roleId(from)
        UsersTable.update({ (UsersTable.email eq address) and (UsersTable.roleId eq fromId) }) {
            it[roleId] = roleId(to)
        }
    }

    private fun roleId(name: String): Int {
        return RolesTable.select(RolesTable.id).where { RolesTable.name eq name }.single()[RolesTable.id]
    }

    private fun find(id: Int): TeacherEmail {
        return entryQuery()
            .where { TeacherEmailsTable.id eq id }
            .singleOrNull()
            ?.let(::toTeacherEmail)
            ?: throw ApiException(
                status = HttpStatus.NOT_FOUND,
                code = ErrorCode.NOT_FOUND,
                detail = "There is no Teacher Email with id $id.",
            )
    }

    private fun entryQuery(): Query {
        return TeacherEmailsTable
            .join(UsersTable, JoinType.LEFT, TeacherEmailsTable.email, UsersTable.email)
            .join(RolesTable, JoinType.LEFT, UsersTable.roleId, RolesTable.id)
            .select(
                TeacherEmailsTable.id,
                TeacherEmailsTable.email,
                UsersTable.id,
                UsersTable.email,
                UsersTable.username,
                UsersTable.registeredAt,
                RolesTable.name,
            )
    }

    private fun toTeacherEmail(row: ResultRow): TeacherEmail {
        val accountId = row.getOrNull(UsersTable.id)
        return TeacherEmail(
            id = row[TeacherEmailsTable.id],
            email = row[TeacherEmailsTable.email],
            account = accountId?.let {
                UserSummary(
                    id = it,
                    email = row[UsersTable.email],
                    fullName = row[UsersTable.username],
                    role = row[RolesTable.name],
                    registeredAt = row[UsersTable.registeredAt],
                )
            },
        )
    }

    private companion object {
        const val STUDENT = "student"
        const val TEACHER = "teacher"
    }
}
