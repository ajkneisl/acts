package dev.ajkneisl.acts.caldav.models

/** One `.ics` resource inside a collection. */
data class CalendarObject(
    val href: String,
    val etag: String?,
    val data: String?,
)
