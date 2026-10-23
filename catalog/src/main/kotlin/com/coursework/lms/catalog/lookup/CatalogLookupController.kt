package com.coursework.lms.catalog.lookup

import com.coursework.lms.catalog.NamedItemResponse
import com.coursework.lms.catalog.NamedItemResponseMapper
import io.swagger.v3.oas.annotations.Operation
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Not paged: the app shows every value as a filter chip.
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAuthority('books.view')")
class CatalogLookupController(
    private val catalogLookupService: CatalogLookupService,
    private val namedItemResponseMapper: NamedItemResponseMapper,
) {

    @GetMapping("/categories")
    @Operation(summary = "Every category, sorted by name")
    fun categories(): List<NamedItemResponse> {
        return catalogLookupService.categories().map(namedItemResponseMapper::map)
    }

    @GetMapping("/languages")
    @Operation(summary = "Every book language, sorted by name")
    fun languages(): List<NamedItemResponse> {
        return catalogLookupService.languages().map(namedItemResponseMapper::map)
    }

    @GetMapping("/books/owners")
    @Operation(summary = "The teachers who own at least one book, sorted by name; University Books are filtered by `university=true` instead")
    fun owners(): List<NamedItemResponse> {
        return catalogLookupService.owners().map(namedItemResponseMapper::map)
    }
}
