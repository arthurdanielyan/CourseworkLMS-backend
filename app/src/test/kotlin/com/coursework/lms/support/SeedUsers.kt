package com.coursework.lms.support

import com.coursework.lms.core.security.CurrentUser

/** The users V1 seeds, with the permissions their roles hold after V2. */
object SeedUsers {

    val admin = CurrentUser(
        id = 1,
        role = "admin",
        permissions = setOf("books.view", "books.add", "books.delete", "books.edit", "users.manage"),
    )

    val student = CurrentUser(
        id = 2,
        role = "student",
        permissions = setOf("books.view"),
    )

    val otherStudent = CurrentUser(
        id = 3,
        role = "student",
        permissions = setOf("books.view"),
    )

    val teacher = CurrentUser(
        id = 4,
        role = "teacher",
        permissions = setOf("books.view", "books.add", "books.delete.own", "books.edit.own"),
    )
}
