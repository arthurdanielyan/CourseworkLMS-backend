package com.coursework.lms.circulation.reservation

import org.springframework.stereotype.Component

@Component
class BookRefResponseMapper {

    fun map(from: BookRef): BookRefResponse {
        return BookRefResponse(
            id = from.id,
            title = from.title,
            coverImageUrl = from.coverImageUrl,
        )
    }
}
