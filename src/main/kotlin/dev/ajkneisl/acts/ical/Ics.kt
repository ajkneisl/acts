package dev.ajkneisl.acts.ical

import dev.ajkneisl.acts.ical.models.EventTime
import dev.ajkneisl.acts.ical.models.IcsEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Handles ICS. */
object Ics {
    const val PRODID = "-//dev.ajkneisl//acts//EN"

    /** Property to tie the event to a Todoist task. */
    const val TASK_ID_PROPERTY = "X-ACTS-TASK-ID"
    const val LEGACY_TASK_ID_PROPERTY = "X-APPLECALDOIST-TASK-ID"

    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATE_TIME_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val DATE_TIME_LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private val MANAGED =
        setOf(
            "BEGIN",
            "END",
            "UID",
            "DTSTAMP",
            "DTSTART",
            "DTEND",
            "DURATION",
            "SUMMARY",
            "DESCRIPTION",
            "SEQUENCE",
            "LAST-MODIFIED",
            "URL",
            "STATUS",
            "RRULE",
            "CREATED",
            "TRANSP",
            TASK_ID_PROPERTY,
            LEGACY_TASK_ID_PROPERTY,
        )

    fun render(event: IcsEvent, now: Instant = Instant.now()): String {
        val lines = buildList {
            add("BEGIN:VCALENDAR")
            add("VERSION:2.0")
            add("PRODID:$PRODID")
            add("CALSCALE:GREGORIAN")
            add("BEGIN:VEVENT")
            add("UID:${escapeText(event.uid)}")
            add("DTSTAMP:${DATE_TIME_UTC.format(now.atZone(ZoneOffset.UTC))}")
            add(renderTime("DTSTART", event.start))
            add(renderTime("DTEND", event.end))
            add("SUMMARY:${escapeText(event.summary)}")

            if (event.description.isNotEmpty()) {
                add("DESCRIPTION:${escapeText(event.description)}")
            }

            event.url?.let { add("URL:${escapeText(it)}") }
            event.status?.let { add("STATUS:$it") }
            event.recurrenceRule?.let { add("RRULE:$it") }
            event.taskId?.let { add("$TASK_ID_PROPERTY:${escapeText(it)}") }

            add("SEQUENCE:${event.sequence}")
            add(
                "LAST-MODIFIED:" +
                    DATE_TIME_UTC.format((event.lastModified ?: now).atZone(ZoneOffset.UTC))
            )
            addAll(event.unknownLines)
            add("END:VEVENT")
            addAll(event.overrides)
            add("END:VCALENDAR")
        }

        return lines.joinToString("") { fold(it) + "\r\n" }
    }

    /** Change a time to UTC. */
    private fun renderTime(name: String, time: EventTime): String =
        when (time) {
            is EventTime.AllDay -> "$name;VALUE=DATE:${DATE.format(time.date)}"
            is EventTime.Timed ->
                "$name:${DATE_TIME_UTC.format(time.instant.atZone(ZoneOffset.UTC))}"
        }

    /** Returns the first VEVENT in the resource, or null if there is none. */
    fun parse(text: String, fallbackZone: ZoneId = ZoneId.systemDefault()): IcsEvent? {
        val lines = unfold(text)

        var depth = 0
        var inEvent = false
        var endedAt = -1
        val eventLines = mutableListOf<String>()
        for ((index, raw) in lines.withIndex()) {
            val (name, _, value) = splitLine(raw) ?: continue
            val upper = name.uppercase()
            if (upper == "BEGIN" && value.equals("VEVENT", ignoreCase = true) && depth == 0) {
                inEvent = true
                depth = 1
                continue
            }
            if (!inEvent) continue
            if (upper == "BEGIN") depth++
            if (upper == "END") {
                if (value.equals("VEVENT", ignoreCase = true) && depth == 1) {
                    endedAt = index
                    break
                }
                depth--
            }
            eventLines += raw
        }

        if (!inEvent || eventLines.isEmpty()) return null

        val overrides =
            if (endedAt >= 0) {
                lines.drop(endedAt + 1).filterNot {
                    it.trim().equals("END:VCALENDAR", ignoreCase = true)
                }
            } else {
                emptyList()
            }

        var uid: String? = null
        var summary = ""
        var description = ""
        var start: EventTime? = null
        var end: EventTime? = null
        var taskId: String? = null
        var sequence = 0
        var lastModified: Instant? = null
        var url: String? = null
        var status: String? = null
        var rrule: String? = null
        var duration: String? = null
        val unknown = mutableListOf<String>()
        var nested = 0

        for (raw in eventLines) {
            val parsed = splitLine(raw) ?: continue
            val (name, params, value) = parsed
            val upper = name.uppercase()

            if (upper == "BEGIN") nested++
            if (nested > 0) {
                unknown += raw
                if (upper == "END") nested--
                continue
            }

            when (upper) {
                "UID" -> uid = unescapeText(value)
                "SUMMARY" -> summary = unescapeText(value)
                "DESCRIPTION" -> description = unescapeText(value)
                "DTSTART" -> start = parseTime(params, value, fallbackZone)
                "DTEND" -> end = parseTime(params, value, fallbackZone)
                "DURATION" -> duration = value
                TASK_ID_PROPERTY,
                LEGACY_TASK_ID_PROPERTY -> taskId = unescapeText(value)
                "SEQUENCE" -> sequence = value.trim().toIntOrNull() ?: 0
                "LAST-MODIFIED" ->
                    lastModified =
                        (parseTime(params, value, fallbackZone) as? EventTime.Timed)?.instant
                "URL" -> url = unescapeText(value)
                "STATUS" -> status = value.trim()
                "RRULE" -> rrule = value.trim()
                else -> if (upper !in MANAGED) unknown += raw
            }
        }

        val resolvedStart = start ?: return null
        val resolvedEnd =
            end ?: duration?.let { applyDuration(resolvedStart, it) } ?: defaultEnd(resolvedStart)

        return IcsEvent(
            uid = uid ?: return null,
            summary = summary,
            start = resolvedStart,
            end = resolvedEnd,
            description = description,
            taskId = taskId,
            sequence = sequence,
            lastModified = lastModified,
            url = url,
            status = status,
            recurrenceRule = rrule,
            unknownLines = unknown,
            overrides = overrides,
        )
    }

    private fun defaultEnd(start: EventTime): EventTime =
        when (start) {
            is EventTime.AllDay -> EventTime.AllDay(start.date.plusDays(1))
            is EventTime.Timed -> start
        }

    internal fun applyDuration(start: EventTime, spec: String): EventTime? {
        val m =
            Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$)?")
                .find(spec.trim().uppercase()) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        val weeks = m.groupValues[2].toLongOrNull() ?: 0
        val days = m.groupValues[3].toLongOrNull() ?: 0
        val hours = m.groupValues[4].toLongOrNull() ?: 0
        val minutes = m.groupValues[5].toLongOrNull() ?: 0
        val seconds = m.groupValues[6].toLongOrNull() ?: 0
        val totalSeconds =
            sign * (((weeks * 7 + days) * 24 + hours) * 3600 + minutes * 60 + seconds)
        return when (start) {
            is EventTime.AllDay -> EventTime.AllDay(start.date.plusDays(sign * (weeks * 7 + days)))
            is EventTime.Timed -> EventTime.Timed(start.instant.plusSeconds(totalSeconds))
        }
    }

    private fun parseTime(
        params: Map<String, String>,
        value: String,
        fallbackZone: ZoneId,
    ): EventTime? {
        val v = value.trim()
        if (v.isEmpty()) return null
        if (params["VALUE"]?.uppercase() == "DATE" || (v.length == 8 && !v.contains('T'))) {
            return runCatching { EventTime.AllDay(LocalDate.parse(v, DATE)) }.getOrNull()
        }
        if (v.endsWith("Z")) {
            return runCatching {
                EventTime.Timed(
                    LocalDateTime.parse(v.dropLast(1), DATE_TIME_LOCAL).toInstant(ZoneOffset.UTC)
                )
            }
                .getOrNull()
        }
        // Either TZID-qualified or floating. Apple writes IANA zone ids, so ZoneId resolves them.
        val zone =
            params["TZID"]?.let { tz -> runCatching { ZoneId.of(tz.trim('"')) }.getOrNull() }
                ?: fallbackZone
        return runCatching {
            EventTime.Timed(LocalDateTime.parse(v, DATE_TIME_LOCAL).atZone(zone).toInstant())
        }
            .getOrNull()
    }

    /** name, params, value. Returns null for blank lines. */
    internal fun splitLine(line: String): Triple<String, Map<String, String>, String>? {
        if (line.isBlank()) return null
        var i = 0
        var inQuotes = false
        var colon = -1
        while (i < line.length) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            if (c == ':' && !inQuotes) {
                colon = i
                break
            }
            i++
        }
        if (colon < 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)

        val parts = splitParams(head)
        val name = parts.first()
        val params =
            parts
                .drop(1)
                .mapNotNull { p ->
                    val eq = p.indexOf('=')
                    if (eq < 0) null
                    else p.substring(0, eq).uppercase() to p.substring(eq + 1).trim('"')
                }
                .toMap()
        return Triple(name, params, value)
    }

    /** Splits on `;` while respecting quoted parameter values. */
    private fun splitParams(head: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (c in head) {
            when {
                c == '"' -> {
                    inQuotes = !inQuotes
                    sb.append(c)
                }
                c == ';' && !inQuotes -> {
                    out += sb.toString()
                    sb.clear()
                }
                else -> sb.append(c)
            }
        }
        out += sb.toString()
        return out
    }

    /**
     * Reverses RFC 5545 line folding: CRLF (or LF) followed by a space or tab is a continuation.
     */
    internal fun unfold(text: String): List<String> {
        val normalised = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = mutableListOf<String>()
        for (line in normalised.split('\n')) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + line.substring(1)
            } else {
                out += line
            }
        }
        return out.filter { it.isNotBlank() }
    }

    /** Folds to 75 octets per line. Octets, not characters, so never mid-character. */
    internal fun fold(line: String, limit: Int = 75): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limit) return line
        val sb = StringBuilder()
        var start = 0
        var first = true
        while (start < bytes.size) {
            // Continuation lines spend one octet on the leading space.
            val budget = if (first) limit else limit - 1
            var take = minOf(budget, bytes.size - start)
            // Back off until we are on a character boundary (continuation bytes are 10xxxxxx).
            while (
                take > 0 &&
                    start + take < bytes.size &&
                    (bytes[start + take].toInt() and 0xC0) == 0x80
            ) {
                take--
            }
            if (take <= 0) take = minOf(budget, bytes.size - start)
            if (!first) sb.append("\r\n ")
            sb.append(String(bytes, start, take, Charsets.UTF_8))
            start += take
            first = false
        }
        return sb.toString()
    }

    internal fun escapeText(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> {}
                else -> append(c)
            }
        }
    }

    internal fun unescapeText(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val n = value[i + 1]) {
                    'n',
                    'N' -> append('\n')
                    '\\' -> append('\\')
                    ';' -> append(';')
                    ',' -> append(',')
                    else -> append(n)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
