package com.coursework.lms.engagement.review

import com.coursework.lms.catalog.NamedItemResponse
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import java.time.LocalDate

data class ReviewRequest(
    @Min(1)
    @Max(5)
    @field:Schema(example = "5")
    val stars: Int,
    @Size(max = 2000)
    @field:Schema(description = "Optional; leave it out to give only stars", example = "Clear, practical and worth rereading.")
    val text: String?,
)

data class ReviewResponse(
    @field:Schema(example = "12")
    val id: Int,
    @field:Schema(
        description = "null once the author's account is deleted; show it as written by a deleted user",
        example = """{"id": 2, "name": "Anna Petrosyan"}""",
    )
    val author: NamedItemResponse?,
    @field:Schema(example = "5")
    val stars: Int,
    @field:Schema(description = "null when the reviewer gave only stars", example = "Clear, practical and worth rereading.")
    val text: String?,
    @field:Schema(example = "2026-04-09")
    val createdAt: LocalDate,
    @field:Schema(description = "Whether the logged-in user wrote it", example = "false")
    val mine: Boolean,
)

data class ReviewSummaryResponse(
    @field:Schema(description = "Average stars, rounded to one decimal; null while there are no Reviews", example = "4.7")
    val average: Double?,
    @field:Schema(example = "3")
    val total: Int,
    @field:Schema(example = "0")
    val oneStar: Int,
    @field:Schema(example = "0")
    val twoStars: Int,
    @field:Schema(example = "0")
    val threeStars: Int,
    @field:Schema(example = "1")
    val fourStars: Int,
    @field:Schema(example = "2")
    val fiveStars: Int,
    @field:Schema(description = "The logged-in user's own Review; null if they haven't written one")
    val myReview: ReviewResponse?,
)
