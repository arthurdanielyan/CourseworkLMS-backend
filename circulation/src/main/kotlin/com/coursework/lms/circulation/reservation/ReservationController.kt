package com.coursework.lms.circulation.reservation

import com.coursework.lms.core.error.ApiException
import com.coursework.lms.core.error.ErrorCode
import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import com.coursework.lms.core.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class ReservationController(
    private val reservationService: ReservationService,
    private val reservationResponseMapper: ReservationResponseMapper,
) {

    @PostMapping("/books/{book_id}/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('reservations.create')")
    @Operation(summary = "Reserves a copy of a book for the logged-in user, to be picked up within the Pickup Window")
    fun reserve(
        @PathVariable("book_id") bookId: Int,
        @RequestBody request: ReserveRequest,
        @AuthenticationPrincipal user: CurrentUser,
    ): ReservationResponse {
        val reservation = reservationService.reserve(
            bookId = bookId,
            userId = user.id,
            returnDeadline = request.returnDeadline,
        )
        return reservationResponseMapper.map(reservation)
    }

    @GetMapping("/reservations/mine")
    @PreAuthorize("hasAuthority('reservations.create')")
    @Operation(summary = "The logged-in user's Reservations, the newest first")
    fun mine(
        @Parameter(description = "Only these statuses; repeat the parameter for more than one, e.g. status=reserved&status=borrowed")
        @RequestParam("status", required = false)
        statuses: List<String>?,
        @Valid @ModelAttribute pageQuery: PageQuery,
        @AuthenticationPrincipal user: CurrentUser,
    ): PageResponse<ReservationResponse> {
        return reservationService.mine(user.id, parseStatuses(statuses), pageQuery).map(reservationResponseMapper::map)
    }

    @PostMapping("/reservations/{id}/cancel")
    @PreAuthorize("hasAuthority('reservations.create')")
    @Operation(summary = "Cancels one of the logged-in user's reserved Reservations")
    fun cancel(
        @PathVariable id: Int,
        @AuthenticationPrincipal user: CurrentUser,
    ): ReservationResponse {
        return reservationResponseMapper.map(reservationService.cancel(id, user.id))
    }

    @GetMapping("/reservations")
    @PreAuthorize("hasAuthority('reservations.manage')")
    @Operation(summary = "Everyone's Reservations, the newest first; admins only")
    fun list(
        @Parameter(description = "Only these statuses; repeat the parameter for more than one")
        @RequestParam("status", required = false)
        statuses: List<String>?,
        @Parameter(description = "true: only Overdue ones (borrowed and past their return deadline)")
        @RequestParam(defaultValue = "false")
        overdue: Boolean,
        @RequestParam("user_id", required = false)
        userId: Int?,
        @RequestParam("book_id", required = false)
        bookId: Int?,
        @Valid @ModelAttribute pageQuery: PageQuery,
    ): PageResponse<ReservationResponse> {
        val filter = ReservationFilter(
            statuses = parseStatuses(statuses),
            overdue = overdue,
            userId = userId,
            bookId = bookId,
        )
        return reservationService.list(filter, pageQuery).map(reservationResponseMapper::map)
    }

    @PostMapping("/reservations/{id}/hand-over")
    @PreAuthorize("hasAuthority('reservations.manage')")
    @Operation(summary = "Records that the reserved copy was handed to its user; admins only")
    fun handOver(@PathVariable id: Int): ReservationResponse {
        return reservationResponseMapper.map(reservationService.handOver(id))
    }

    @PostMapping("/reservations/{id}/return")
    @PreAuthorize("hasAuthority('reservations.manage')")
    @Operation(summary = "Records that the borrowed copy is back in the library; admins only")
    fun takeBack(@PathVariable id: Int): ReservationResponse {
        return reservationResponseMapper.map(reservationService.takeBack(id))
    }

    private fun parseStatuses(values: List<String>?): List<ReservationStatus> {
        return values.orEmpty().map { value ->
            ReservationStatus.fromValue(value) ?: throw ApiException(
                status = HttpStatus.BAD_REQUEST,
                code = ErrorCode.BAD_REQUEST,
                detail = "Unknown status '$value'.",
            )
        }
    }
}
