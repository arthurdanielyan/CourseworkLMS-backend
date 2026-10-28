package com.coursework.lms.circulation.waitlist

import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import com.coursework.lms.core.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAuthority('reservations.create')")
class WaitlistController(
    private val waitlistService: WaitlistService,
    private val waitlistEntryResponseMapper: WaitlistEntryResponseMapper,
) {

    @PostMapping("/books/{book_id}/waitlist")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Puts the logged-in user in line for an Unavailable book")
    fun join(
        @PathVariable("book_id") bookId: Int,
        @AuthenticationPrincipal user: CurrentUser,
    ): WaitlistEntryResponse {
        return waitlistEntryResponseMapper.map(waitlistService.join(bookId, user.id))
    }

    @DeleteMapping("/books/{book_id}/waitlist")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Takes the logged-in user out of line, declining their Offer if they hold one")
    fun leave(
        @PathVariable("book_id") bookId: Int,
        @AuthenticationPrincipal user: CurrentUser,
    ) {
        waitlistService.leave(bookId, user.id)
    }

    @GetMapping("/waitlist/mine")
    @Operation(summary = "The waitlists the logged-in user is on, with their place in line or their Offer")
    fun mine(
        @Valid @ModelAttribute pageQuery: PageQuery,
        @AuthenticationPrincipal user: CurrentUser,
    ): PageResponse<WaitlistEntryResponse> {
        return waitlistService.mine(user.id, pageQuery).map(waitlistEntryResponseMapper::map)
    }
}
