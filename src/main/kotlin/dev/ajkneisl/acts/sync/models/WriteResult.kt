package dev.ajkneisl.acts.sync.models

/** Where a written event ended up, and its new change token. */
data class WriteResult(val href: String, val etag: String?)
