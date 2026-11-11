package com.coursework.lms.notifications

import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import com.coursework.lms.core.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(
    private val notificationService: NotificationService,
    private val notificationResponseMapper: NotificationResponseMapper,
) {

    @GetMapping
    @Operation(summary = "The logged-in user's notifications, the newest first")
    fun list(
        @RequestParam("unread_only", defaultValue = "false")
        unreadOnly: Boolean,
        @Valid @ModelAttribute pageQuery: PageQuery,
        @AuthenticationPrincipal user: CurrentUser,
    ): PageResponse<NotificationResponse> {
        return notificationService.list(user.id, unreadOnly, pageQuery).map(notificationResponseMapper::map)
    }

    @GetMapping("/unread-count")
    @Operation(summary = "How many notifications are unread, for a badge")
    fun unreadCount(@AuthenticationPrincipal user: CurrentUser): UnreadCountResponse {
        return UnreadCountResponse(count = notificationService.unreadCount(user.id))
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Marks one notification as read")
    fun markRead(
        @PathVariable id: Int,
        @AuthenticationPrincipal user: CurrentUser,
    ) {
        notificationService.markRead(id, user.id)
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Marks every notification as read")
    fun markAllRead(@AuthenticationPrincipal user: CurrentUser) {
        notificationService.markAllRead(user.id)
    }
}
