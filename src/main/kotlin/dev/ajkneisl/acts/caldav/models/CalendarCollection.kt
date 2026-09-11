package dev.ajkneisl.acts.caldav.models

/** A calendar collection discovered under the principal's calendar-home-set. */
data class CalendarCollection(
    val href: String,
    val displayName: String?,
    val ctag: String?,
    val supportsEvents: Boolean,
)
