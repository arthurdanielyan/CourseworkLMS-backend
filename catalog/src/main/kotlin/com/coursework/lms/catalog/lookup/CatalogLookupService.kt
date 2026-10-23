package com.coursework.lms.catalog.lookup

import com.coursework.lms.catalog.NamedItem
import com.coursework.lms.catalog.db.BookLanguagesTable
import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.catalog.db.CategoriesTable
import com.coursework.lms.identity.db.UsersTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
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
            .withDistinct()
            .orderBy(UsersTable.username to SortOrder.ASC)
            .map { NamedItem(it[UsersTable.id], it[UsersTable.username]) }
    }
}
