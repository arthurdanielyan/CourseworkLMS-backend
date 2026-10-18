package com.coursework.lms.core.db

import com.coursework.lms.core.security.CurrentUser
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

/** Decides which user the database acts for in the transactions the current thread opens. */
@Component
class DbUserContext {

    private val override = ThreadLocal<Int?>()

    /** The user to act for: the one set by [runAs], otherwise the authenticated [CurrentUser], otherwise none. */
    fun currentUserId(): Int? {
        return override.get() ?: (SecurityContextHolder.getContext().authentication?.principal as? CurrentUser)?.id
    }

    /**
     * Runs [block] with the database acting for [userId], for work done on behalf of a user who is not the
     * authenticated one — such as resetting the password of a user who cannot log in. Must be called outside a
     * transaction, since only transactions [block] opens pick the user up.
     */
    fun <T> runAs(
        userId: Int,
        block: () -> T,
    ): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "runAs must be called outside a transaction"
        }
        val previous = override.get()
        override.set(userId)
        try {
            return block()
        } finally {
            override.set(previous)
        }
    }
}
