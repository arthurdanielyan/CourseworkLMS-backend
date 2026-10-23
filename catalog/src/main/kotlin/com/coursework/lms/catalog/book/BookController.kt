package com.coursework.lms.catalog.book

import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/books")
@PreAuthorize("hasAuthority('books.view')")
class BookController(
    private val bookCatalogService: BookCatalogService,
    private val bookSummaryResponseMapper: BookSummaryResponseMapper,
    private val bookDetailsResponseMapper: BookDetailsResponseMapper,
) {

    @GetMapping
    fun search(
        @Parameter(description = "Text in the title, the subtitle or an author's name, ignoring case", example = "clean code")
        @RequestParam(required = false)
        q: String?,
        @Parameter(description = "Text in an author's name, ignoring case", example = "martin")
        @RequestParam(required = false)
        author: String?,
        @Parameter(example = "2008")
        @RequestParam("publication_year", required = false)
        publicationYear: Int?,
        @Parameter(description = "Books in any of these categories; repeat the parameter for more than one")
        @RequestParam("category_id", required = false)
        categoryIds: List<Int>?,
        @Parameter(description = "Books in any of these languages; repeat the parameter for more than one")
        @RequestParam("language_id", required = false)
        languageIds: List<Int>?,
        @Parameter(description = "true: only Available books; false: only Unavailable ones; leave out for both")
        @RequestParam(required = false)
        available: Boolean?,
        @Parameter(description = "Books owned by any of these teachers; repeat the parameter for more than one")
        @RequestParam("owner_id", required = false)
        ownerIds: List<Int>?,
        @Parameter(description = "true: University Books match too, alongside any owner_id books")
        @RequestParam(defaultValue = "false")
        university: Boolean,
        @Valid @ModelAttribute pageQuery: PageQuery,
    ): PageResponse<BookSummaryResponse> {
        val search = BookSearch(
            text = q?.trim()?.takeIf { it.isNotEmpty() },
            author = author?.trim()?.takeIf { it.isNotEmpty() },
            publicationYear = publicationYear,
            categoryIds = categoryIds.orEmpty(),
            languageIds = languageIds.orEmpty(),
            available = available,
            ownerIds = ownerIds.orEmpty(),
            universityBooks = university,
        )
        return bookCatalogService.search(search, pageQuery).map(bookSummaryResponseMapper::map)
    }

    @GetMapping("/{id}")
    fun details(@PathVariable id: Int): BookDetailsResponse {
        return bookDetailsResponseMapper.map(bookCatalogService.details(id))
    }
}
