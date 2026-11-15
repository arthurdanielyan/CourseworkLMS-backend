package com.coursework.lms.engagement.review

import com.coursework.lms.catalog.NamedItemResponseMapper
import org.springframework.stereotype.Component

@Component
class ReviewResponseMapper(
    private val namedItemResponseMapper: NamedItemResponseMapper,
) {

    fun map(from: Review): ReviewResponse {
        return ReviewResponse(
            id = from.id,
            author = from.author?.let(namedItemResponseMapper::map),
            stars = from.stars,
            text = from.text,
            createdAt = from.createdAt,
            mine = from.mine,
        )
    }
}
