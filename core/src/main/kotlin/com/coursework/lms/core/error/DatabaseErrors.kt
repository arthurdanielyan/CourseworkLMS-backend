package com.coursework.lms.core.error

import org.postgresql.util.PSQLException
import java.sql.SQLException

/** SQLSTATE codes the backend answers specifically, including the ones the database triggers raise. */
internal object SqlState {

    const val INSUFFICIENT_PRIVILEGE = "42501"
    const val NOT_AUTHENTICATED = "L0001"
    const val UNIQUE_VIOLATION = "23505"
    const val FOREIGN_KEY_VIOLATION = "23503"
    const val CHECK_VIOLATION = "23514"
}

/** The innermost database error behind a failure, with the server's own message when there is one. */
internal data class DatabaseError(
    val sqlState: String,
    val message: String,
)

internal fun Throwable.findDatabaseError(): DatabaseError? {
    return generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .lastOrNull { it.sqlState != null }
        ?.let { error ->
            DatabaseError(
                sqlState = error.sqlState,
                message = (error as? PSQLException)?.serverErrorMessage?.message ?: error.message.orEmpty(),
            )
        }
}
