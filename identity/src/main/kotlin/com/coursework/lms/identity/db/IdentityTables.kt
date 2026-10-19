package com.coursework.lms.identity.db

import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE
import org.jetbrains.exposed.v1.core.ReferenceOption.NO_ACTION
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

// The migrations write `DEFAULT now()` and leave foreign keys at PostgreSQL's NO ACTION; Exposed's own spellings
// (CURRENT_TIMESTAMP, RESTRICT) mean the same but would fail the schema drift check.
private val now = CustomFunction<OffsetDateTime>("now", JavaOffsetDateTimeColumnType())

object RolesTable : Table("roles") {

    val id = integer("id").autoIncrement()
    val name = varchar("name", 50).uniqueIndex("roles_name_key")

    override val primaryKey = PrimaryKey(id, name = "roles_pkey")
}

object PermissionsTable : Table("permissions") {

    val id = integer("id").autoIncrement()
    val name = varchar("name", 100).uniqueIndex("permissions_name_key")

    override val primaryKey = PrimaryKey(id, name = "permissions_pkey")
}

object RolePermissionsTable : Table("role_permissions") {

    val roleId = integer("role_id")
        .references(RolesTable.id, onDelete = CASCADE, onUpdate = NO_ACTION, fkName = "role_permissions_role_id_fkey")
    val permissionId = integer("permission_id").references(
        PermissionsTable.id,
        onDelete = CASCADE,
        onUpdate = NO_ACTION,
        fkName = "role_permissions_permission_id_fkey",
    )

    override val primaryKey = PrimaryKey(roleId, permissionId, name = "role_permissions_pkey")
}

object UsersTable : Table("users") {

    val id = integer("id").autoIncrement()
    val email = varchar("email", 255).uniqueIndex("users_unique")
    val username = varchar("username", 100)
    val passwordHash = text("password_hash")
    val registeredAt = timestampWithTimeZone("registered_at").defaultExpression(now)
    val roleId = integer("role_id")
        .references(RolesTable.id, onDelete = NO_ACTION, onUpdate = NO_ACTION, fkName = "users_role_id_fkey")

    override val primaryKey = PrimaryKey(id, name = "users_pk")
}

object PendingRegistrationsTable : Table("pending_registrations") {

    val email = varchar("email", 255)
    val username = varchar("username", 100)
    val passwordHash = text("password_hash")
    val codeHash = text("code_hash")
    val codeExpiresAt = timestampWithTimeZone("code_expires_at")
    val failedAttempts = short("failed_attempts").default(0)
    val lastSentAt = timestampWithTimeZone("last_sent_at")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(now)

    override val primaryKey = PrimaryKey(email, name = "pending_registrations_pkey")
}

object RefreshTokensTable : Table("refresh_tokens") {

    val id = long("id").autoIncrement()
    val userId = integer("user_id")
        .references(UsersTable.id, onDelete = CASCADE, onUpdate = NO_ACTION, fkName = "refresh_tokens_user_id_fkey")
    val tokenHash = text("token_hash").uniqueIndex("refresh_tokens_token_hash_key")
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(now)
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    val replacedById = long("replaced_by_id")
        .references(id, onDelete = NO_ACTION, onUpdate = NO_ACTION, fkName = "refresh_tokens_replaced_by_id_fkey")
        .nullable()

    override val primaryKey = PrimaryKey(id, name = "refresh_tokens_pkey")

    init {
        index("refresh_tokens_user_id_idx", false, userId)
    }
}
