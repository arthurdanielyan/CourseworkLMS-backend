package com.coursework.lms.catalog.lookup

import com.coursework.lms.catalog.NamedItemResponse
import com.coursework.lms.catalog.NamedItemResponseMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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
    @ApiResponse(
        responseCode = "200",
        content = [
            Content(
                array = ArraySchema(schema = Schema(implementation = NamedItemResponse::class)),
                examples = [ExampleObject("""[{"id": 14, "name": "Architecture"}, {"id": 12, "name": "Art"}]""")],
            ),
        ],
    )
    fun categories(): List<NamedItemResponse> {
        return catalogLookupService.categories().map(namedItemResponseMapper::map)
    }

    @GetMapping("/languages")
    @Operation(summary = "Every book language, sorted by name")
    @ApiResponse(
        responseCode = "200",
        content = [
            Content(
                array = ArraySchema(schema = Schema(implementation = NamedItemResponse::class)),
                examples = [
                    ExampleObject(
                        """[{"id": 2, "name": "Armenian"}, {"id": 1, "name": "English"}, {"id": 3, "name": "Russian"}]""",
                    ),
                ],
            ),
        ],
    )
    fun languages(): List<NamedItemResponse> {
        return catalogLookupService.languages().map(namedItemResponseMapper::map)
    }

    @GetMapping("/books/owners")
    @Operation(summary = "The teachers who own at least one book, sorted by name; University Books are filtered by `university=true` instead")
    @ApiResponse(
        responseCode = "200",
        content = [
            Content(
                array = ArraySchema(schema = Schema(implementation = NamedItemResponse::class)),
                examples = [ExampleObject("""[{"id": 4, "name": "Ella Hovhannisyan"}]""")],
            ),
        ],
    )
    fun owners(): List<NamedItemResponse> {
        return catalogLookupService.owners().map(namedItemResponseMapper::map)
    }

    @GetMapping("/authors")
    @Operation(summary = "Authors whose name contains the text, sorted by name, for autocomplete")
    fun authors(
        @Parameter(example = "martin")
        @RequestParam
        q: String,
        @RequestParam(defaultValue = "10")
        limit: Int,
    ): List<NamedItemResponse> {
        return catalogLookupService.authors(q.trim(), limit.coerceIn(1, 50)).map(namedItemResponseMapper::map)
    }

    @GetMapping("/publishers")
    @Operation(summary = "Publishers whose name contains the text, sorted by name, for autocomplete")
    fun publishers(
        @Parameter(example = "pear")
        @RequestParam
        q: String,
        @RequestParam(defaultValue = "10")
        limit: Int,
    ): List<NamedItemResponse> {
        return catalogLookupService.publishers(q.trim(), limit.coerceIn(1, 50)).map(namedItemResponseMapper::map)
    }
}
