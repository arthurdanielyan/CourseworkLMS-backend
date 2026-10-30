package com.coursework.lms.catalog.book

// Reservations belong to :circulation, which depends on this module, so deleting a book asks through this interface.
interface BookLoans {

    fun hasBorrowedCopies(bookId: Int): Boolean

    /** Empties the book's Waitlist and cancels its Reserved Reservations, for a book about to be hidden. */
    fun withdrawHolds(bookId: Int)
}
