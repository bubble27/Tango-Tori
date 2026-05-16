package com.tangotori.app.domain.util

internal fun String.htmlEscape(): String = buildString(length) {
    for (c in this@htmlEscape) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}
