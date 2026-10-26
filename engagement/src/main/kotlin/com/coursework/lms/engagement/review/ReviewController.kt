package com.coursework.lms.engagement.review

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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAuthority('books.view')")
class ReviewController(
    private val reviewService: ReviewService,
    private val reviewResponseMapper: ReviewResponseMapper,
    private val reviewSummaryResponseMapper: ReviewSummaryResponseMapper,
) {

    @GetMapping("/books/{book_id}/reviews")
    @Operation(summary = "A book's Reviews, newest first")
    fun list(
        @PathVariable("book_id") bookId: Int,
        @Parameter(
            description = "Only Reviews with these stars; repeat the parameter for more than one. " +
                "Positive Reviews are stars=4&stars=5, Critical ones stars=1&stars=2.",
        )
        @RequestParam("stars", required = false)
        stars: List<Int>?,
        @Valid @ModelAttribute pageQuery: PageQuery,
        @AuthenticationPrincipal user: CurrentUser,
    ): PageResponse<ReviewResponse> {
        return reviewService.list(bookId, stars.orEmpty(), user.id, pageQuery).map(reviewResponseMapper::map)
    }

    @GetMapping("/books/{book_id}/reviews/summary")
    @Operation(summary = "How many Reviews a book has for each number of stars, and the logged-in user's own")
    fun summary(
        @PathVariable("book_id") bookId: Int,
        @AuthenticationPrincipal user: CurrentUser,
    ): ReviewSummaryResponse {
        return reviewSummaryResponseMapper.map(reviewService.summary(bookId, user.id))
    }

    @PutMapping("/books/{book_id}/reviews/mine")
    @Operation(summary = "Writes the logged-in user's Review of a book, replacing their earlier one")
    fun writeMine(
        @PathVariable("book_id") bookId: Int,
        @Valid @RequestBody request: ReviewRequest,
        @AuthenticationPrincipal user: CurrentUser,
    ): ReviewResponse {
        val review = reviewService.writeMine(
            bookId = bookId,
            userId = user.id,
            stars = request.stars,
            text = request.text?.trim()?.takeIf { it.isNotEmpty() },
        )
        return reviewResponseMapper.map(review)
    }

    @DeleteMapping("/books/{book_id}/reviews/mine")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Deletes the logged-in user's Review of a book; deleting one that isn't there changes nothing")
    fun deleteMine(
        @PathVariable("book_id") bookId: Int,
        @AuthenticationPrincipal user: CurrentUser,
    ) {
        reviewService.deleteMine(bookId, user.id)
    }

    @DeleteMapping("/reviews/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('reviews.moderate')")
    @Operation(summary = "Deletes anyone's Review; admins only")
    fun delete(@PathVariable id: Int) {
        reviewService.delete(id)
    }
}
