package com.coursework.lms.catalog.file

import com.coursework.lms.core.error.ApiException
import com.coursework.lms.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

@RestController
class FileController(
    private val storedFiles: StoredFiles,
) {

    @GetMapping("/api/v1/files/{id}", produces = [MediaType.ALL_VALUE])
    @Operation(summary = "A book's cover or PDF; needs no token. Links come from the book's cover_image_url and pdf_url.")
    fun download(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val file = storedFiles.load(id) ?: throw ApiException(
            status = HttpStatus.NOT_FOUND,
            code = ErrorCode.NOT_FOUND,
            detail = "There is no file with id $id.",
        )
        val disposition = file.downloadName
            ?.let { ContentDisposition.inline().filename(it, StandardCharsets.UTF_8).build() }
            ?: ContentDisposition.inline().build()
        // A file never changes under its id: a new upload gets a new one.
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .body(file.data)
    }
}
