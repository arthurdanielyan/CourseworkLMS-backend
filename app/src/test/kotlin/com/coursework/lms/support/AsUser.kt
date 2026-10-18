package com.coursework.lms.support

import com.coursework.lms.core.db.DbUserContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/** Runs SQL as the application role in a transaction that is always rolled back, so tests leave no data behind. */
@Component
class AsUser(
    private val userContext: DbUserContext,
    private val transactionTemplate: TransactionTemplate,
    private val jdbcClient: JdbcClient,
) {

    /** Runs [block] with the database acting for [userId], or for nobody when it is null. */
    fun <T> run(
        userId: Int?,
        block: (JdbcClient) -> T,
    ): T {
        val work = {
            transactionTemplate.execute { status ->
                status.setRollbackOnly()
                block(jdbcClient)
            } as T
        }
        return if (userId == null) work() else userContext.runAs(userId, work)
    }
}
