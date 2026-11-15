package com.coursework.lms.circulation

/** Values of a problem response's `code` that only circulation answers with. */
object CirculationErrorCode {

    const val NO_COPIES_AVAILABLE = "no_copies_available"
    const val ALREADY_RESERVED = "already_reserved"
    const val INVALID_RETURN_DATE = "invalid_return_date"
    const val INVALID_STATUS = "invalid_status"
    const val BOOK_AVAILABLE = "book_available"
    const val ALREADY_WAITING = "already_waiting"
    const val HAS_BORROWED_COPIES = "has_borrowed_copies"
}
