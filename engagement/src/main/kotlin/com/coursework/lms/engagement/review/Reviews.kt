package com.coursework.lms.engagement.review

import com.coursework.lms.catalog.NamedItem
import java.time.LocalDate

/** [mine] tells whether the logged-in user wrote it; [author] is null once their account is deleted. */
data class Review(
    val id: Int,
    val author: NamedItem?,
    val stars: Int,
    val text: String?,
    val createdAt: LocalDate,
    val mine: Boolean,
)

/** [starCounts] holds every star value from 1 to 5, with zero for the ones nobody gave. */
data class ReviewSummary(
    val starCounts: Map<Int, Int>,
    val myReview: Review?,
) {

    val total: Int
        get() = starCounts.values.sum()

    val average: Float?
        get() = if (total == 0) null else starCounts.entries.sumOf { it.key * it.value }.toFloat() / total
}
