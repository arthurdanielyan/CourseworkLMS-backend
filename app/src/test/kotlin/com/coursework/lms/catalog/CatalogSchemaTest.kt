package com.coursework.lms.catalog

import com.coursework.lms.catalog.db.AuthorsTable
import com.coursework.lms.catalog.db.BookAuthorsTable
import com.coursework.lms.catalog.db.BookLanguagesTable
import com.coursework.lms.catalog.db.BooksCategoriesTable
import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.catalog.db.CategoriesTable
import com.coursework.lms.catalog.db.PublishersTable
import com.coursework.lms.core.testing.assertSchemaMatches
import com.coursework.lms.support.IntegrationTest
import org.junit.jupiter.api.Test

class CatalogSchemaTest : IntegrationTest() {

    @Test
    fun `the catalog tables match the migrated schema`() {
        assertSchemaMatches(
            PublishersTable,
            BookLanguagesTable,
            AuthorsTable,
            CategoriesTable,
            BooksTable,
            BookAuthorsTable,
            BooksCategoriesTable,
        )
    }
}
