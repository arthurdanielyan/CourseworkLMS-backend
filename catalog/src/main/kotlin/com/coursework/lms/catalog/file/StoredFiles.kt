package com.coursework.lms.catalog.file

import com.coursework.lms.catalog.CatalogErrorCode
import com.coursework.lms.catalog.db.BooksTable
import com.coursework.lms.catalog.db.FilesTable
import com.coursework.lms.core.error.ApiException
import com.coursework.lms.core.error.ErrorCode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

/** What a book's file may be: the formats it accepts and how large it may get. */
enum class BookFileKind(
    val maxBytes: Int,
    val formats: List<FileFormat>,
) {

    COVER(5 * 1024 * 1024, listOf(FileFormat.JPEG, FileFormat.PNG, FileFormat.WEBP)),
    PDF(50 * 1024 * 1024, listOf(FileFormat.PDF)),
}

/** A file format, recognised by the bytes it starts with rather than by its name. */
enum class FileFormat(
    val contentType: String,
) {

    PDF("application/pdf"),
    JPEG("image/jpeg"),
    PNG("image/png"),
    WEBP("image/webp"),
    ;

    fun matches(bytes: ByteArray): Boolean {
        return when (this) {
            PDF -> bytes.startsWith("%PDF-".toByteArray())
            JPEG -> bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            PNG -> bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            WEBP -> bytes.startsWith("RIFF".toByteArray()) && bytes.size >= 12 &&
                bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        return size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)
    }
}

/** A file read back for download; [downloadName] is set for a book's PDF. */
data class StoredFile(
    val contentType: String,
    val data: ByteArray,
    val downloadName: String?,
)

/** Keeps book files in the database and builds the links clients load them from. */
@Component
class StoredFiles {

    /** Stores [bytes] as a [kind] file and returns its id; refuses other formats and oversized files. */
    fun store(
        bytes: ByteArray,
        kind: BookFileKind,
    ): UUID {
        if (bytes.size > kind.maxBytes) {
            throw ApiException(
                status = HttpStatus.PAYLOAD_TOO_LARGE,
                code = ErrorCode.FILE_TOO_LARGE,
                detail = "The file is larger than ${kind.maxBytes / (1024 * 1024)} MB.",
            )
        }
        val format = kind.formats.firstOrNull { it.matches(bytes) }
            ?: throw ApiException(
                status = HttpStatus.BAD_REQUEST,
                code = CatalogErrorCode.UNSUPPORTED_FILE_TYPE,
                detail = "Expected ${kind.formats.joinToString(" or ") { it.name }}.",
            )
        val id = UUID.randomUUID()
        FilesTable.insert {
            it[FilesTable.id] = id
            it[contentType] = format.contentType
            it[sizeBytes] = bytes.size
            it[data] = bytes
        }
        return id
    }

    fun delete(id: UUID) {
        FilesTable.deleteWhere { FilesTable.id eq id }
    }

    @Transactional(readOnly = true)
    fun load(id: UUID): StoredFile? {
        val row = FilesTable.selectAll().where { FilesTable.id eq id }.singleOrNull() ?: return null
        val title = BooksTable.select(BooksTable.title).where { BooksTable.pdfFileId eq id }.firstOrNull()?.get(BooksTable.title)
        return StoredFile(
            contentType = row[FilesTable.contentType],
            data = row[FilesTable.data],
            downloadName = title?.let { "$it.pdf" },
        )
    }

    /** The absolute link to file [id] on the server handling the current request. */
    fun urlOf(id: UUID): String {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/v1/files/{id}")
            .buildAndExpand(id)
            .toUriString()
    }
}
