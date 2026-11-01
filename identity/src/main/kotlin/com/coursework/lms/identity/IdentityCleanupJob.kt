package com.coursework.lms.identity

import com.coursework.lms.identity.db.PendingRegistrationsTable
import com.coursework.lms.identity.db.RefreshTokensTable
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Deletes refresh tokens past their expiry and Pending Registrations kept past their retention. */
@Component
class IdentityCleanupJob(
    private val properties: IdentityProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(IdentityCleanupJob::class.java)

    /** Runs nightly at 03:00 unless `lms.identity.cleanup-cron` says otherwise. */
    @Scheduled(cron = "\${lms.identity.cleanup-cron:0 0 3 * * *}")
    @Transactional
    fun run() {
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        // Revoked tokens are kept until they expire: until then, one coming back reveals a stolen copy.
        val refreshTokens = RefreshTokensTable.deleteWhere { RefreshTokensTable.expiresAt less now }
        val pendingRegistrations = PendingRegistrationsTable.deleteWhere {
            PendingRegistrationsTable.codeExpiresAt less now.minus(properties.pendingRegistrationRetention)
        }
        log.info("Deleted {} expired refresh tokens and {} stale pending registrations", refreshTokens, pendingRegistrations)
    }
}
