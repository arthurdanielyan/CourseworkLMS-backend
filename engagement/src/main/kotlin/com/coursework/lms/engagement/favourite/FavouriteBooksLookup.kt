package com.coursework.lms.engagement.favourite

import com.coursework.lms.catalog.book.FavouriteBooks
import com.coursework.lms.engagement.db.UsersFavouritesTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class FavouriteBooksLookup : FavouriteBooks {

    @Transactional(readOnly = true)
    override fun favouredBy(
        userId: Int,
        bookIds: List<Int>,
    ): Set<Int> {
        if (bookIds.isEmpty()) {
            return emptySet()
        }
        return UsersFavouritesTable
            .select(UsersFavouritesTable.bookId)
            .where { (UsersFavouritesTable.userId eq userId) and (UsersFavouritesTable.bookId inList bookIds) }
            .map { it[UsersFavouritesTable.bookId] }
            .toSet()
    }
}
