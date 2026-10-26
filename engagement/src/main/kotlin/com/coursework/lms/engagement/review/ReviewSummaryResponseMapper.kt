package com.coursework.lms.engagement.review

import com.coursework.lms.catalog.book.roundRating
import org.springframework.stereotype.Component

@Component
class ReviewSummaryResponseMapper(
    private val reviewResponseMapper: ReviewResponseMapper,
) {

    fun map(from: ReviewSummary): ReviewSummaryResponse {
        return ReviewSummaryResponse(
            average = roundRating(from.average),
            total = from.total,
            oneStar = from.starCounts.getValue(1),
            twoStars = from.starCounts.getValue(2),
            threeStars = from.starCounts.getValue(3),
            fourStars = from.starCounts.getValue(4),
            fiveStars = from.starCounts.getValue(5),
            myReview = from.myReview?.let(reviewResponseMapper::map),
        )
    }
}
