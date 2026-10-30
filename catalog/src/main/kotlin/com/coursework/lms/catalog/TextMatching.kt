package com.coursework.lms.catalog

import org.jetbrains.exposed.v1.core.LikePattern

/** A pattern matching lowercased text that contains [text] anywhere. */
// Escaped, so a % or _ the user types isn't a wildcard.
internal fun containing(text: String): LikePattern {
    val literal = LikePattern.ofLiteral(text.lowercase())
    return LikePattern("%${literal.pattern}%", literal.escapeChar)
}
