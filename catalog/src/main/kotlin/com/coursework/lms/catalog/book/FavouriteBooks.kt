package com.coursework.lms.catalog.book

// Favourites belong to :engagement, which depends on this module, so the catalog asks through this interface.
interface FavouriteBooks {

    /** Which of [bookIds] the user has as Favourites. */
    fun favouredBy(
        userId: Int,
        bookIds: List<Int>,
    ): Set<Int>
}
