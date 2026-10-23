package com.coursework.lms.catalog

import io.swagger.v3.oas.annotations.media.Schema

data class NamedItemResponse(
    @field:Schema(example = "2")
    val id: Int,
    @field:Schema(example = "Software Engineering")
    val name: String,
)
