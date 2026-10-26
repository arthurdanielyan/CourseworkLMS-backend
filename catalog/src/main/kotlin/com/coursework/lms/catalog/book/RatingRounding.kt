package com.coursework.lms.catalog.book

import kotlin.math.roundToInt

fun roundRating(rating: Float?): Double? {
    return rating?.let { (it * 10).roundToInt() / 10.0 }
}
