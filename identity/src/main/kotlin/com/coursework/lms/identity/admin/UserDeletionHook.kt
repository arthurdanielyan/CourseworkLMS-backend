package com.coursework.lms.identity.admin

// Modules that depend on this one keep data about users; they get their say before an account goes.
interface UserDeletionHook {

    /** Prepares [userId]'s data for the account's deletion, or refuses it by throwing an ApiException. */
    fun beforeDelete(userId: Int)
}
