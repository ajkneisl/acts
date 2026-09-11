package dev.ajkneisl.acts.ical.models

import java.time.Instant

/** The part of a VEVENT this sync round-trips. */
data class IcsEvent(
    val uid: String,
    val summary: String,
    val start: EventTime,
    val end: EventTime,
    val description: String = "",
    val taskId: String? = null,
    val sequence: Int = 0,
    val lastModified: Instant? = null,
    val url: String? = null,
    val status: String? = null,
    val recurrenceRule: String? = null,
    val unknownLines: List<String> = emptyList(),
    /**
     * Whole VEVENT components following the first one: the occurrences of a repeating event that
     * somebody moved individually. Carried through untouched so rewriting never discards them.
     */
    val overrides: List<String> = emptyList(),
) {
    val isAllDay: Boolean
        get() = start is EventTime.AllDay
}
