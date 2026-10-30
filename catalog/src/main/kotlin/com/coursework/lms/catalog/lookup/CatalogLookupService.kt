package com.coursework.lms.catalog.lookup

import com.coursework.lms.catalog.NamedItem
import com.coursework.lms.catalog.containing
import com.coursework.lms.catalog.db.AuthorsTable
import com.coursework.lms.catalog.db.BookLanguagesTable
import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.catalog.db.CategoriesTable
import com.coursework.lms.catalog.db.PublishersTable
import com.coursework.lms.identity.db.UsersTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CatalogLookupService {

    @Transactional(readOnly = true)
    fun categories(): List<NamedItem> {
        return CategoriesTable.selectAll()
            .orderBy(CategoriesTable.name to SortOrder.ASC)
            .map { NamedItem(it[CategoriesTable.id], it[CategoriesTable.name]) }
    }

    @Transactional(readOnly = true)
    fun languages(): List<NamedItem> {
        return BookLanguagesTable.selectAll()
            .orderBy(BookLanguagesTable.name to SortOrder.ASC)
            .map { NamedItem(it[BookLanguagesTable.id], it[BookLanguagesTable.name]) }
    }

    @Transactional(readOnly = true)
    fun owners(): List<NamedItem> {
        return UsersTable
            .join(BooksTable, JoinType.INNER, UsersTable.id, BooksTable.addedBy)
            .select(UsersTable.id, UsersTable.username)
            .where { BooksTable.deletedAt.isNull() }
            .withDistinct()
            .orderBy(UsersTable.username to SortOrder.ASC)
            .map { NamedItem(it[UsersTable.id], it[UsersTable.username]) }
    }

    /** Up to [limit] authors whose name contains [text], for autocomplete. */
    @Transactional(readOnly = true)
    fun authors(
        text: String,
        limit: Int,
    ): List<NamedItem> {
        return AuthorsTable.selectAll()
            .where { AuthorsTable.name.lowerCase() like containing(text) }
            .orderBy(AuthorsTable.name to SortOrder.ASC)
            .limit(limit)
            .map { NamedItem(it[AuthorsTable.id], it[AuthorsTable.name]) }
    }

    /** Up to [limit] publishers whose name contains [text], for autocomplete. */
    @Transactional(readOnly = true)
    fun publishers(
        text: String,
        limit: Int,
    ): List<NamedItem> {
        return PublishersTable.selectAll()
            .where { PublishersTable.name.lowerCase() like containing(text) }
            .orderBy(PublishersTable.name to SortOrder.ASC)
            .limit(limit)
            .map { NamedItem(it[PublishersTable.id], it[PublishersTable.name]) }
    }
}
