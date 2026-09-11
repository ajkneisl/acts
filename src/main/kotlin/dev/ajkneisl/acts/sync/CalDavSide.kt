package dev.ajkneisl.acts.sync

import dev.ajkneisl.acts.caldav.CalDavClient
import dev.ajkneisl.acts.caldav.PreconditionFailed
import dev.ajkneisl.acts.caldav.models.CalendarObject
import dev.ajkneisl.acts.caldav.resolve
import dev.ajkneisl.acts.sync.models.WriteResult

class CalDavSide(
    private val client: CalDavClient,
    override val calendarUrl: String,
) : CalendarSide {

    override fun collectionToken(): String? = client.collectionCtag(calendarUrl)

    override fun listEvents(): List<CalendarObject> = client.listEvents(calendarUrl)

    override fun create(taskId: String, ics: String): WriteResult {
        val href = resolve(calendarUrl, "todoist-$taskId.ics")
        val etag = conflictAware { client.putEvent(href, ics, ifMatch = null, ifNoneMatch = true) }
        // Some servers omit the ETag on write; re-read rather than lose the baseline.
        return WriteResult(href, etag ?: client.etagOf(href))
    }

    override fun update(href: String, ics: String, ifMatch: String?): WriteResult {
        val etag = conflictAware { client.putEvent(href, ics, ifMatch = ifMatch, ifNoneMatch = false) }
        return WriteResult(href, etag ?: client.etagOf(href))
    }

    override fun delete(href: String, ifMatch: String?) {
        conflictAware { client.deleteEvent(href, ifMatch) }
    }

    private fun <T> conflictAware(block: () -> T): T = try {
        block()
    } catch (e: PreconditionFailed) {
        throw SyncConflictException(e.message ?: "calendar changed under us")
    }
}
