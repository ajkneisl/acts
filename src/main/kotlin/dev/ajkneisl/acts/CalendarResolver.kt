package dev.ajkneisl.acts

import dev.ajkneisl.acts.caldav.CalDavClient
import dev.ajkneisl.acts.config.Setting
import dev.ajkneisl.acts.config.Settings
import org.slf4j.LoggerFactory

/** Resolve the Todoist calendar. */
object CalendarResolver {
    private val log = LoggerFactory.getLogger(CalendarResolver::class.java)

    data class Resolved(val calendarUrl: String, val created: Boolean)

    fun resolve(
        client: CalDavClient,
        settings: Settings,
        cached: String? = null,
    ): Resolved {
        if (cached != null && verify(client, cached)) {
            return Resolved(cached, created = false)
        }

        val principal = client.findPrincipal(settings.text(Setting.CALDAV_URL))
        log.debug("principal: {}", principal)
        val home = client.findCalendarHome(principal)
        log.debug("calendar home: {}", home)

        val calendars = client.listCalendars(home)
        val wanted = settings.text(Setting.CALENDAR_NAME)
        val match = calendars.firstOrNull {
            it.supportsEvents && it.displayName.equals(wanted, ignoreCase = true)
        }
        if (match != null) {
            log.info("Using existing calendar '{}'", match.displayName)
            return Resolved(match.href, created = false)
        }

        log.info(
            "No calendar named '{}' found among {}; creating it",
            wanted,
            calendars.mapNotNull { it.displayName },
        )
        return Resolved(client.createCalendar(home, wanted), created = true)
    }

    /** Cheap liveness check so a cached URL that has since been deleted triggers rediscovery. */
    private fun verify(
        client: CalDavClient,
        url: String,
    ): Boolean = runCatching {
        client.collectionCtag(url) != null
    }
        .getOrElse { e ->
            if (e is dev.ajkneisl.acts.caldav.CalDavException)
                log.info("Cached calendar {} is no longer usable, rediscovering", url)
            false
        }
}
