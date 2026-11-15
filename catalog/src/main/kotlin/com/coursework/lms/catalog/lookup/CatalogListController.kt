package com.coursework.lms.catalog.lookup

import com.coursework.lms.catalog.NamedItemResponse
import com.coursework.lms.catalog.NamedItemResponseMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class CatalogListItemRequest(
    @NotBlank
    @Size(max = 50)
    @field:Schema(example = "Robotics")
    val name: String,
)

@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAuthority('catalog.manage')")
class CatalogListController(
    private val catalogListService: CatalogListService,
    private val namedItemResponseMapper: NamedItemResponseMapper,
) {

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Adds a category; admins only")
    fun addCategory(@Valid @RequestBody request: CatalogListItemRequest): NamedItemResponse {
        return namedItemResponseMapper.map(catalogListService.add(CatalogList.CATEGORIES, request.name.trim()))
    }

    @PutMapping("/categories/{id}")
    @Operation(summary = "Renames a category; admins only")
    fun renameCategory(
        @PathVariable id: Int,
        @Valid @RequestBody request: CatalogListItemRequest,
    ): NamedItemResponse {
        return namedItemResponseMapper.map(catalogListService.rename(CatalogList.CATEGORIES, id, request.name.trim()))
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Removes a category no book is filed under; admins only")
    fun removeCategory(@PathVariable id: Int) {
        catalogListService.remove(CatalogList.CATEGORIES, id)
    }

    @PostMapping("/languages")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Adds a book language; admins only")
    fun addLanguage(@Valid @RequestBody request: CatalogListItemRequest): NamedItemResponse {
        return namedItemResponseMapper.map(catalogListService.add(CatalogList.LANGUAGES, request.name.trim()))
    }

    @PutMapping("/languages/{id}")
    @Operation(summary = "Renames a book language; admins only")
    fun renameLanguage(
        @PathVariable id: Int,
        @Valid @RequestBody request: CatalogListItemRequest,
    ): NamedItemResponse {
        return namedItemResponseMapper.map(catalogListService.rename(CatalogList.LANGUAGES, id, request.name.trim()))
    }

    @DeleteMapping("/languages/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Removes a book language no book is in; admins only")
    fun removeLanguage(@PathVariable id: Int) {
        catalogListService.remove(CatalogList.LANGUAGES, id)
    }
}
