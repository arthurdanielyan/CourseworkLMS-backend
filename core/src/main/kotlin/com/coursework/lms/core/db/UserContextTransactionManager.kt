package com.coursework.lms.core.db

import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus

/**
 * A transaction manager that, on top of [delegate], tells the database which user every new transaction acts for
 * by setting `app.current_user_id` for that transaction only.
 */
class UserContextTransactionManager(
    private val delegate: PlatformTransactionManager,
    private val userContext: DbUserContext,
    private val jdbcClient: ObjectProvider<JdbcClient>,
) : PlatformTransactionManager {

    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
        val status = delegate.getTransaction(definition)
        // A transaction that joins an outer one already carries the outer one's user.
        if (status.isNewTransaction) {
            try {
                applyCurrentUser()
            } catch (error: RuntimeException) {
                delegate.rollback(status)
                throw error
            }
        }
        return status
    }

    override fun commit(status: TransactionStatus) {
        delegate.commit(status)
    }

    override fun rollback(status: TransactionStatus) {
        delegate.rollback(status)
    }

    private fun applyCurrentUser() {
        val userId = userContext.currentUserId() ?: return
        // is_local = true scopes the setting to this transaction, so it never leaks to the next borrower of the
        // pooled connection (ADR 0001). JdbcClient joins the delegate's transaction through the bound connection.
        jdbcClient.getObject()
            .sql("select set_config('app.current_user_id', ?, true)")
            .param(userId.toString())
            .query()
            .singleValue()
    }
}
