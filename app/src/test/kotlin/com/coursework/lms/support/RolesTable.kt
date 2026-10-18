package com.coursework.lms.support

import org.jetbrains.exposed.v1.core.Table

/** The `roles` table as Exposed sees it, used to exercise paging and the schema drift check. */
object RolesTable : Table("roles") {

    val id = integer("id").autoIncrement()
    val name = varchar("name", 50).uniqueIndex("roles_name_key")

    override val primaryKey = PrimaryKey(id, name = "roles_pkey")
}
