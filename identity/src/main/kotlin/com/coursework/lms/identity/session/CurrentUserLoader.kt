package com.coursework.lms.identity.session

import com.coursework.lms.core.security.CurrentUser
import com.coursework.lms.identity.db.PermissionsTable
import com.coursework.lms.identity.db.RolePermissionsTable
import com.coursework.lms.identity.db.RolesTable
import com.coursework.lms.identity.db.UsersTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.springframework.stereotype.Component

/** Reads a user's current role and permissions from the database. */
@Component
class CurrentUserLoader {

    fun load(userId: Int): CurrentUser {
        val rows = UsersTable
            .join(RolesTable, JoinType.INNER, UsersTable.roleId, RolesTable.id)
            .join(RolePermissionsTable, JoinType.LEFT, RolesTable.id, RolePermissionsTable.roleId)
            .join(PermissionsTable, JoinType.LEFT, RolePermissionsTable.permissionId, PermissionsTable.id)
            .select(RolesTable.name, PermissionsTable.name)
            .where { UsersTable.id eq userId }
            .toList()
        val role = checkNotNull(rows.firstOrNull()?.get(RolesTable.name)) { "user $userId does not exist" }
        return CurrentUser(
            id = userId,
            role = role,
            permissions = rows.mapNotNull { it.getOrNull(PermissionsTable.name) }.toSet(),
        )
    }
}
