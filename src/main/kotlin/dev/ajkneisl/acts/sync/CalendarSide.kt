package dev.ajkneisl.acts.sync

import dev.ajkneisl.acts.caldav.models.CalendarObject
import dev.ajkneisl.acts.sync.models.WriteResult

/** The calendar side of the sync, narrowed to what the engine does. */
interface CalendarSide {
    /** Stable identifier for the collection, shown to the user and cached in the state file. */
    val calendarUrl: String

    /** A token that changes whenever anything in the collection does. */
    fun collectionToken(): String?

    fun listEvents(): List<CalendarObject>

    /** Create an event for [taskId]. Throws [SyncConflictException] if one exists. */
    fun create(taskId: String, ics: String): WriteResult

    /** Update an event, only if its token still matches [ifMatch]. */
    fun update(href: String, ics: String, ifMatch: String?): WriteResult

    fun delete(href: String, ifMatch: String?)
}
