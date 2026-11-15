package com.coursework.lms.catalog.lookup

import com.coursework.lms.catalog.CatalogErrorCode
import com.coursework.lms.catalog.NamedItem
import com.coursework.lms.catalog.db.BookLanguagesTable
import com.coursework.lms.catalog.db.BooksCategoriesTable
import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.catalog.db.CategoriesTable
import com.coursework.lms.core.error.ApiException
import com.coursework.lms.core.error.ErrorCode
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** The two short lists a book is filed under. */
enum class CatalogList(
    val label: String,
) {

    CATEGORIES("category"),
    LANGUAGES("language"),
}

/** Lets admins add, rename and remove categories and languages. */
@Service
class CatalogListService {

    @Transactional
    fun add(
        list: CatalogList,
        name: String,
    ): NamedItem {
        val (table, id, nameColumn) = columnsOf(list)
        refuseTakenName(list, name, exceptId = null)
        val newId = table.insert { it[nameColumn] = name }[id]
        return NamedItem(newId, name)
    }

    @Transactional
    fun rename(
        list: CatalogList,
        itemId: Int,
        name: String,
    ): NamedItem {
        val (table, id, nameColumn) = columnsOf(list)
        requireExists(list, itemId)
        refuseTakenName(list, name, exceptId = itemId)
        table.update({ id eq itemId }) { it[nameColumn] = name }
        return NamedItem(itemId, name)
    }

    /** Removes an entry no book uses; one still in use is refused rather than taken off its books. */
    @Transactional
    fun remove(
        list: CatalogList,
        itemId: Int,
    ) {
        val (table, id, _) = columnsOf(list)
        requireExists(list, itemId)
        val inUse = when (list) {
            CatalogList.CATEGORIES -> BooksCategoriesTable.select(BooksCategoriesTable.bookId)
                .where { BooksCategoriesTable.categoryId eq itemId }
                .any()
            CatalogList.LANGUAGES -> BooksTable.select(BooksTable.id).where { BooksTable.languageId eq itemId }.any()
        }
        if (inUse) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = CatalogErrorCode.IN_USE,
                detail = "Some books are filed under this ${list.label}. Move them to another one first.",
            )
        }
        table.deleteWhere { id eq itemId }
    }

    private fun requireExists(
        list: CatalogList,
        itemId: Int,
    ) {
        val (table, id, _) = columnsOf(list)
        if (!table.select(id).where { id eq itemId }.any()) {
            throw ApiException(
                status = HttpStatus.NOT_FOUND,
                code = ErrorCode.NOT_FOUND,
                detail = "There is no ${list.label} with id $itemId.",
            )
        }
    }

    // Names are unique ignoring case (V12), so "physics" can't sit next to "Physics".
    private fun refuseTakenName(
        list: CatalogList,
        name: String,
        exceptId: Int?,
    ) {
        val (table, id, nameColumn) = columnsOf(list)
        val sameName = nameColumn.lowerCase() eq stringParam(name).lowerCase()
        val taken = table.select(id)
            .where { if (exceptId == null) sameName else sameName and (id neq exceptId) }
            .any()
        if (taken) {
            throw ApiException(
                status = HttpStatus.CONFLICT,
                code = CatalogErrorCode.NAME_TAKEN,
                detail = "There already is a ${list.label} called '$name'.",
            )
        }
    }

    private fun columnsOf(list: CatalogList): Triple<Table, Column<Int>, Column<String>> {
        return when (list) {
            CatalogList.CATEGORIES -> Triple(CategoriesTable, CategoriesTable.id, CategoriesTable.name)
            CatalogList.LANGUAGES -> Triple(BookLanguagesTable, BookLanguagesTable.id, BookLanguagesTable.name)
        }
    }
}
