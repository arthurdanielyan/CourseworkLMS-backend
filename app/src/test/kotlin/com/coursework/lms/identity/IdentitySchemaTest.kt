package com.coursework.lms.identity

import com.coursework.lms.core.testing.assertSchemaMatches
import com.coursework.lms.identity.db.PendingRegistrationsTable
import com.coursework.lms.identity.db.PermissionsTable
import com.coursework.lms.identity.db.RefreshTokensTable
import com.coursework.lms.identity.db.RolePermissionsTable
import com.coursework.lms.identity.db.RolesTable
import com.coursework.lms.identity.db.UsersTable
import com.coursework.lms.support.IntegrationTest
import org.junit.jupiter.api.Test

class IdentitySchemaTest : IntegrationTest() {

    @Test
    fun `the identity tables match the migrated schema`() {
        assertSchemaMatches(
            RolesTable,
            PermissionsTable,
            RolePermissionsTable,
            UsersTable,
            PendingRegistrationsTable,
            RefreshTokensTable,
        )
    }
}
