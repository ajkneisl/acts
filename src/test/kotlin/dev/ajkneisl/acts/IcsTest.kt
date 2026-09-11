package dev.ajkneisl.acts

import dev.ajkneisl.acts.ical.Ics
import dev.ajkneisl.acts.ical.models.EventTime
import dev.ajkneisl.acts.ical.models.IcsEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IcsTest {

    private val chicago = ZoneId.of("America/Chicago")

    private fun timed(iso: String) = EventTime.Timed(Instant.parse(iso))

    @Test
    fun `timed event round-trips through render and parse`() {
        val event = IcsEvent(
            uid = "todoist-123@acts.ajkneisl.dev",
            summary = "Write the sync engine",
            start = timed("2026-09-10T15:00:00Z"),
            end = timed("2026-09-10T16:00:00Z"),
            description = "with tests",
            taskId = "123",
        )
        val parsed = assertNotNull(Ics.parse(Ics.render(event)))
        assertEquals(event.uid, parsed.uid)
        assertEquals(event.summary, parsed.summary)
        assertEquals(event.description, parsed.description)
        assertEquals(event.start, parsed.start)
        assertEquals(event.end, parsed.end)
        assertEquals("123", parsed.taskId)
    }

    @Test
    fun `all-day event uses a DATE value and an exclusive end`() {
        val event = IcsEvent(
            uid = "u1",
            summary = "Pay rent",
            start = EventTime.AllDay(LocalDate.of(2026, 9, 1)),
            end = EventTime.AllDay(LocalDate.of(2026, 9, 2)),
        )
        val ics = Ics.render(event)
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20260901"), ics)
        assertTrue(ics.contains("DTEND;VALUE=DATE:20260902"), ics)

        val parsed = assertNotNull(Ics.parse(ics))
        assertEquals(EventTime.AllDay(LocalDate.of(2026, 9, 1)), parsed.start)
        assertTrue(parsed.isAllDay)
    }

    @Test
    fun `timed values are always written in UTC so no VTIMEZONE is needed`() {
        val ics = Ics.render(
            IcsEvent("u", "s", timed("2026-09-10T15:00:00Z"), timed("2026-09-10T15:30:00Z"))
        )
        assertTrue(ics.contains("DTSTART:20260910T150000Z"), ics)
        assertTrue(!ics.contains("VTIMEZONE"), "should not emit a VTIMEZONE component")
    }

    @Test
    fun `parses the TZID form Apple Calendar writes back`() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTIMEZONE
            TZID:America/Chicago
            BEGIN:STANDARD
            DTSTART:19701101T020000
            TZOFFSETFROM:-0500
            TZOFFSETTO:-0600
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:abc
            SUMMARY:Moved in Calendar.app
            DTSTART;TZID=America/Chicago:20260910T090000
            DTEND;TZID=America/Chicago:20260910T100000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val parsed = assertNotNull(Ics.parse(ics, chicago))
        // 09:00 Chicago in September is CDT (UTC-5), so 14:00Z.
        assertEquals(timed("2026-09-10T14:00:00Z"), parsed.start)
        assertEquals("Moved in Calendar.app", parsed.summary)
    }

    @Test
    fun `skips the VTIMEZONE component instead of reading its DTSTART`() {
        // Regression guard: VTIMEZONE contains its own DTSTART, which must not win.
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VTIMEZONE
            TZID:America/Chicago
            BEGIN:DAYLIGHT
            DTSTART:19700308T020000
            END:DAYLIGHT
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:abc
            DTSTART:20260910T150000Z
            DTEND:20260910T160000Z
            SUMMARY:Real event
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val parsed = assertNotNull(Ics.parse(ics, chicago))
        assertEquals(timed("2026-09-10T15:00:00Z"), parsed.start)
    }

    @Test
    fun `escapes and unescapes text specials`() {
        val nasty = "Buy milk; eggs, bread \\ and\nnewline"
        val event = IcsEvent("u", nasty, timed("2026-01-01T00:00:00Z"), timed("2026-01-01T01:00:00Z"), description = nasty)
        val parsed = assertNotNull(Ics.parse(Ics.render(event)))
        assertEquals(nasty, parsed.summary)
        assertEquals(nasty, parsed.description)
    }

    @Test
    fun `folds long lines to 75 octets and unfolds them again`() {
        val long = "x".repeat(400)
        val rendered = Ics.render(
            IcsEvent("u", long, timed("2026-01-01T00:00:00Z"), timed("2026-01-01T01:00:00Z"))
        )
        rendered.split("\r\n").filter { it.isNotEmpty() }.forEach {
            assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75, "line too long: ${it.length}")
        }
        assertEquals(long, assertNotNull(Ics.parse(rendered)).summary)
    }

    @Test
    fun `folding never splits a multi-byte character`() {
        // Four-byte emoji straddling the 75-octet boundary.
        val summary = "📅".repeat(60)
        val rendered = Ics.render(
            IcsEvent("u", summary, timed("2026-01-01T00:00:00Z"), timed("2026-01-01T01:00:00Z"))
        )
        assertTrue(!rendered.contains("�"), "folding produced a replacement character")
        assertEquals(summary, assertNotNull(Ics.parse(rendered)).summary)
    }

    @Test
    fun `preserves unknown components such as VALARM`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:abc
            SUMMARY:With a reminder
            DTSTART:20260910T150000Z
            DTEND:20260910T160000Z
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val parsed = assertNotNull(Ics.parse(ics))
        assertTrue(parsed.unknownLines.any { it.startsWith("BEGIN:VALARM") }, parsed.unknownLines.toString())
        assertTrue(Ics.render(parsed).contains("TRIGGER:-PT15M"), "alarm must survive a write-back")
    }

    @Test
    fun `derives the end from DURATION when DTEND is absent`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:abc
            SUMMARY:Duration only
            DTSTART:20260910T150000Z
            DURATION:PT45M
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val parsed = assertNotNull(Ics.parse(ics))
        assertEquals(timed("2026-09-10T15:45:00Z"), parsed.end)
    }

    @Test
    fun `returns null when there is no VEVENT`() {
        assertNull(Ics.parse("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n"))
    }

    @Test
    fun `captures a recurrence rule so the engine can refuse it`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:abc
            SUMMARY:Standup
            DTSTART:20260910T150000Z
            DTEND:20260910T151500Z
            RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", assertNotNull(Ics.parse(ics)).recurrenceRule)
    }
}
