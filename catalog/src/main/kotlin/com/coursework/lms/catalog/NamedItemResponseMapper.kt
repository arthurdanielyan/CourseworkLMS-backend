package com.coursework.lms.catalog

import org.springframework.stereotype.Component

@Component
class NamedItemResponseMapper {

    fun map(from: NamedItem): NamedItemResponse {
        return NamedItemResponse(
            id = from.id,
            name = from.name,
        )
    }
}
