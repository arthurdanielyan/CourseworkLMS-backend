package com.coursework.lms.identity.admin

import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import com.coursework.lms.core.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAuthority('users.manage')")
class AdminController(
    private val userAdminService: UserAdminService,
    private val teacherEmailService: TeacherEmailService,
    private val userSummaryResponseMapper: UserSummaryResponseMapper,
    private val teacherEmailResponseMapper: TeacherEmailResponseMapper,
) {

    @GetMapping("/users")
    @Operation(summary = "Users, sorted by name; admins only")
    fun users(
        @Parameter(description = "Text in the name or email, ignoring case")
        @RequestParam(required = false)
        q: String?,
        @RequestParam(required = false)
        role: String?,
        @Valid @ModelAttribute pageQuery: PageQuery,
    ): PageResponse<UserSummaryResponse> {
        return userAdminService.list(q?.trim()?.takeIf { it.isNotEmpty() }, role, pageQuery)
            .map(userSummaryResponseMapper::map)
    }

    @PutMapping("/users/{id}/role")
    @Operation(summary = "Changes a user's role; takes effect at their next token refresh, within 15 minutes")
    fun changeRole(
        @PathVariable id: Int,
        @RequestBody request: ChangeRoleRequest,
        @AuthenticationPrincipal admin: CurrentUser,
    ): UserSummaryResponse {
        return userSummaryResponseMapper.map(userAdminService.changeRole(id, request.role, admin.id))
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Deletes an account. Refused while it has a borrowed copy. Its reviews stay without an author " +
            "and its books become University Books; everything else of it goes.",
    )
    fun deleteUser(
        @PathVariable id: Int,
        @AuthenticationPrincipal admin: CurrentUser,
    ) {
        userAdminService.delete(id, admin.id)
    }

    @GetMapping("/teacher-emails")
    @Operation(summary = "The Teacher Emails, sorted by address, each with the account registered under it")
    fun teacherEmails(@Valid @ModelAttribute pageQuery: PageQuery): PageResponse<TeacherEmailResponse> {
        return teacherEmailService.list(pageQuery).map(teacherEmailResponseMapper::map)
    }

    @PostMapping("/teacher-emails")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Adds a Teacher Email; a student already registered under it becomes a teacher")
    fun addTeacherEmail(@Valid @RequestBody request: AddTeacherEmailRequest): TeacherEmailResponse {
        return teacherEmailResponseMapper.map(teacherEmailService.add(request.email))
    }

    @DeleteMapping("/teacher-emails/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Removes a Teacher Email; a teacher registered under it becomes a student")
    fun removeTeacherEmail(@PathVariable id: Int) {
        teacherEmailService.remove(id)
    }
}
