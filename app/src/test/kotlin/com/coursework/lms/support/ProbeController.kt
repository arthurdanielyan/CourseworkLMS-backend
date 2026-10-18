package com.coursework.lms.support

import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import com.coursework.lms.core.paging.toPage
import com.coursework.lms.core.security.CurrentUser
import com.coursework.lms.core.security.PublicEndpoints
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionAspectSupport
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Endpoints that exist only in tests, exercising the core plumbing the way a feature would. */
@RestController
@RequestMapping("/api/v1/test-probe")
class ProbeController(
    private val probe: ProbeService,
) {

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal user: CurrentUser): Map<String, Any?> {
        return mapOf("user_id" to user.id, "db_user_id" to probe.dbUserId())
    }

    @GetMapping("/public/db-user")
    fun anonymousDbUser(): Map<String, Any?> {
        return mapOf("db_user_id" to probe.dbUserId())
    }

    @GetMapping("/admin-only")
    @PreAuthorize("hasAuthority('users.manage')")
    fun adminOnly(): Map<String, Any?> {
        return mapOf("ok" to true)
    }

    @PostMapping("/books")
    fun insertBook(): Map<String, Any?> {
        return probe.insertBookAndRollBack()
    }

    @PostMapping("/public/books")
    fun insertBookAnonymously(): Map<String, Any?> {
        return probe.insertBookAndRollBack()
    }

    @GetMapping("/roles")
    fun roles(@Valid @ModelAttribute pageQuery: PageQuery): PageResponse<RoleItem> {
        return probe.roles(pageQuery)
    }

    @PostMapping("/validate")
    fun validate(@Valid @RequestBody body: ProbeBody): ProbeBody {
        return body
    }
}

/** A role as the probe lists it; its two-word property names show the JSON naming. */
data class RoleItem(
    val roleId: Int,
    val roleName: String,
)

/** A request body with one constrained, two-word field. */
data class ProbeBody(
    @NotBlank
    val displayName: String,
)

@Service
class ProbeService(
    private val jdbcClient: JdbcClient,
) {

    @Transactional(readOnly = true)
    fun dbUserId(): String? {
        return jdbcClient.sql("select nullif(current_setting('app.current_user_id', true), '')")
            .query(String::class.java)
            .optional()
            .orElse(null)
    }

    @Transactional
    fun insertBookAndRollBack(): Map<String, Any?> {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
        return jdbcClient.sql(
            """
            insert into books (title, description, total_copies, copies_available, publisher_id, language_id,
                               max_reservation_days)
            values ('Probe', 'Probe book', 5, 5, 1, 1, 30)
            returning added_by, total_copies, max_reservation_days
            """.trimIndent(),
        ).query().singleRow()
    }

    @Transactional(readOnly = true)
    fun roles(pageQuery: PageQuery): PageResponse<RoleItem> {
        return RolesTable.selectAll()
            .orderBy(RolesTable.id to SortOrder.ASC)
            .toPage(pageQuery) { row ->
                RoleItem(
                    roleId = row[RolesTable.id],
                    roleName = row[RolesTable.name],
                )
            }
    }
}

@Configuration
class ProbeConfig {

    @Bean
    fun probePublicEndpoints(): PublicEndpoints {
        return PublicEndpoints(patterns = listOf("/api/v1/test-probe/public/**"))
    }
}
