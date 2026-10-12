package com.opnavigation.app.data

fun describeFailure(error: Throwable): String {
    val parts = ArrayList<String>(4)
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 4) {
        val message = current.message?.trim().orEmpty()
        val piece = if (message.isEmpty()) {
            current.javaClass.simpleName
        } else {
            "${current.javaClass.simpleName}: $message"
        }
        if (parts.lastOrNull() != piece) parts += piece
        current = current.cause
        depth++
    }
    return parts.joinToString(" | ").ifBlank { error.javaClass.simpleName }
}

fun httpFailure(summary: String, statusCode: Int, body: String): String {
    val snippet = body.trim().replace(Regex("\\s+"), " ").take(220)
    val detail = if (snippet.isEmpty()) "HTTP $statusCode" else "HTTP $statusCode: $snippet"
    return if (summary.contains("HTTP $statusCode")) summary else "$summary ($detail)"
}
