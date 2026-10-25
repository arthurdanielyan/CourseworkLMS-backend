package com.coursework.lms.engagement.favourite

import com.coursework.lms.catalog.book.BookSummaryResponse
import com.coursework.lms.catalog.book.BookSummaryResponseMapper
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/favourites")
@PreAuthorize("hasAuthority('books.view')")
class FavouriteController(
    private val favouriteService: FavouriteService,
    private val bookSummaryResponseMapper: BookSummaryResponseMapper,
) {

    @GetMapping
    @Operation(summary = "The logged-in user's Favourites, the most recently added first")
    fun list(
        @Valid @ModelAttribute pageQuery: PageQuery,
        @AuthenticationPrincipal user: CurrentUser,
    ): PageResponse<BookSummaryResponse> {
        return favouriteService.list(user.id, pageQuery).map(bookSummaryResponseMapper::map)
    }

    @PutMapping("/{book_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Adds a book to the Favourites; adding it again changes nothing")
    fun add(
        @PathVariable("book_id") bookId: Int,
        @AuthenticationPrincipal user: CurrentUser,
    ) {
        favouriteService.add(user.id, bookId)
    }

    @DeleteMapping("/{book_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Removes a book from the Favourites; removing one that isn't there changes nothing")
    fun remove(
        @PathVariable("book_id") bookId: Int,
        @AuthenticationPrincipal user: CurrentUser,
    ) {
        favouriteService.remove(user.id, bookId)
    }
}
