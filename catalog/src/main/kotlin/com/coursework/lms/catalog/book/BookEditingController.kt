package com.coursework.lms.catalog.book

import com.coursework.lms.catalog.file.BookFileKind
import com.coursework.lms.core.error.ApiException
import com.coursework.lms.core.error.ErrorCode
import com.coursework.lms.core.paging.PageQuery
import com.coursework.lms.core.paging.PageResponse
import com.coursework.lms.core.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/books")
class BookEditingController(
    private val bookEditingService: BookEditingService,
    private val bookCatalogService: BookCatalogService,
    private val bookFormMapper: BookFormMapper,
    private val bookDetailsResponseMapper: BookDetailsResponseMapper,
    private val bookSummaryResponseMapper: BookSummaryResponseMapper,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('books.add')")
    @Operation(summary = "Adds a book: a University Book when an admin adds it, otherwise the teacher's own")
    fun create(
        @Valid @RequestBody request: BookRequest,
        @AuthenticationPrincipal user: CurrentUser,
    ): BookDetailsResponse {
        return bookDetailsResponseMapper.map(bookEditingService.create(bookFormMapper.map(request), user))
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('books.edit', 'books.edit.own')")
    @Operation(summary = "Replaces a book's details; teachers edit only their own books")
    fun update(
        @PathVariable id: Int,
        @Valid @RequestBody request: BookRequest,
        @AuthenticationPrincipal user: CurrentUser,
    ): BookDetailsResponse {
        return bookDetailsResponseMapper.map(bookEditingService.update(id, bookFormMapper.map(request), user))
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('books.delete', 'books.delete.own')")
    @Operation(
        summary = "Deletes a book; teachers delete their own while the library holds no copies. " +
            "A book with borrowed copies is hidden until they are returned.",
    )
    fun delete(
        @PathVariable id: Int,
        @AuthenticationPrincipal user: CurrentUser,
    ) {
        bookEditingService.delete(id, user)
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('books.add')")
    @Operation(summary = "The books the logged-in teacher owns, sorted by title")
    fun mine(
        @Valid @ModelAttribute pageQuery: PageQuery,
        @AuthenticationPrincipal user: CurrentUser,
    ): PageResponse<BookSummaryResponse> {
        return bookCatalogService.search(BookSearch(ownerIds = listOf(user.id)), user.id, pageQuery)
            .map(bookSummaryResponseMapper::map)
    }

    @PutMapping("/{id}/cover", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("hasAnyAuthority('books.edit', 'books.edit.own')")
    @Operation(summary = "Uploads the book's cover: JPEG, PNG or WebP, up to 5 MB")
    fun attachCover(
        @PathVariable id: Int,
        @RequestPart("file") file: MultipartFile,
        @AuthenticationPrincipal user: CurrentUser,
    ): BookDetailsResponse {
        return bookDetailsResponseMapper.map(bookEditingService.attachFile(id, BookFileKind.COVER, bytesOf(file), user))
    }

    @DeleteMapping("/{id}/cover")
    @PreAuthorize("hasAnyAuthority('books.edit', 'books.edit.own')")
    @Operation(summary = "Removes the book's cover")
    fun detachCover(
        @PathVariable id: Int,
        @AuthenticationPrincipal user: CurrentUser,
    ): BookDetailsResponse {
        return bookDetailsResponseMapper.map(bookEditingService.detachFile(id, BookFileKind.COVER, user))
    }

    @PutMapping("/{id}/pdf", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("hasAnyAuthority('books.edit', 'books.edit.own')")
    @Operation(summary = "Uploads the book's PDF, up to 50 MB")
    fun attachPdf(
        @PathVariable id: Int,
        @RequestPart("file") file: MultipartFile,
        @AuthenticationPrincipal user: CurrentUser,
    ): BookDetailsResponse {
        return bookDetailsResponseMapper.map(bookEditingService.attachFile(id, BookFileKind.PDF, bytesOf(file), user))
    }

    @DeleteMapping("/{id}/pdf")
    @PreAuthorize("hasAnyAuthority('books.edit', 'books.edit.own')")
    @Operation(summary = "Removes the book's PDF")
    fun detachPdf(
        @PathVariable id: Int,
        @AuthenticationPrincipal user: CurrentUser,
    ): BookDetailsResponse {
        return bookDetailsResponseMapper.map(bookEditingService.detachFile(id, BookFileKind.PDF, user))
    }

    private fun bytesOf(file: MultipartFile): ByteArray {
        if (file.isEmpty) {
            throw ApiException(
                status = HttpStatus.BAD_REQUEST,
                code = ErrorCode.BAD_REQUEST,
                detail = "The file is empty.",
            )
        }
        return file.bytes
    }
}
