package dev.ajkneisl.acts.caldav

/**
 * A CalDAV request that failed.
 *
 * @param message The specific error.
 * @param status An optional HTTP status.
 */
open class CalDavException(
    message: String,
    val status: Int = -1
) : RuntimeException(message)
