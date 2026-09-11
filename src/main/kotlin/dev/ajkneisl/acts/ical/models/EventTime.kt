package dev.ajkneisl.acts.ical.models

import java.time.Instant
import java.time.LocalDate

/** The start or end of an event: a floating date, or a point in time. */
sealed interface EventTime {
    data class AllDay(val date: LocalDate) : EventTime

    data class Timed(val instant: Instant) : EventTime
}
