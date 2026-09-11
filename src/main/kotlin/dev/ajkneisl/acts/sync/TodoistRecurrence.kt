package dev.ajkneisl.acts.sync

import dev.ajkneisl.acts.todoist.models.TodoistDue

/** Convert Todoist recurrence into an Apple calendar event. */
object TodoistRecurrence {

    private val DAYS =
        mapOf(
            "monday" to "MO",
            "mon" to "MO",
            "tuesday" to "TU",
            "tues" to "TU",
            "tue" to "TU",
            "wednesday" to "WE",
            "weds" to "WE",
            "wed" to "WE",
            "thursday" to "TH",
            "thurs" to "TH",
            "thur" to "TH",
            "thu" to "TH",
            "friday" to "FR",
            "fri" to "FR",
            "saturday" to "SA",
            "sat" to "SA",
            "sunday" to "SU",
            "sun" to "SU",
        )

    private val MONTHS =
        listOf(
            "january",
            "february",
            "march",
            "april",
            "may",
            "june",
            "july",
            "august",
            "september",
            "october",
            "november",
            "december",
        )

    private const val WEEKDAYS = "MO,TU,WE,TH,FR"

    fun toRRule(due: TodoistDue?): String? {
        if (due == null || !due.isRecurring) return null

        due.lang?.let { if (!it.equals("en", ignoreCase = true)) return null }
        val phrase = due.string?.lowercase()?.trim() ?: return null

        if (phrase.contains('!')) return null

        return parse(strip(phrase))
    }

    /** Removes the parts that describe the occurrence, not the pattern. */
    private fun strip(phrase: String): String =
        phrase
            .substringBefore(" at ")
            .substringBefore(" starting ")
            .substringBefore(" from ")
            .substringBefore(" until ")
            .substringBefore(" ending ")
            .trim()
            .removePrefix("every ")
            .trim()
            .ifEmpty { "day" }

    private fun parse(rest: String): String? {
        val interval =
            when {
                rest.startsWith("other ") -> 2
                else -> INTERVAL.find(rest)?.groupValues?.get(1)?.toIntOrNull()
            }
        val tail = rest.removePrefix("other ").let { INTERVAL.replace(it, "").trim() }

        return when {
            tail.isBlank() || tail in setOf("day", "days") -> rule("DAILY", interval)
            tail in setOf("morning", "afternoon", "evening", "night") -> rule("DAILY", interval)
            tail in setOf("week", "weeks") -> rule("WEEKLY", interval)
            tail in setOf("month", "months") -> rule("MONTHLY", interval)
            tail in setOf("year", "years") -> rule("YEARLY", interval)
            tail in setOf("weekday", "weekdays", "workday", "workdays") ->
                rule("WEEKLY", interval, "BYDAY=$WEEKDAYS")

            else -> byDay(tail, interval) ?: byMonthDay(tail, interval)
        }
    }

    /** "mon, wed & fri", or "2 weeks on mon" once the interval has been peeled off. */
    private fun byDay(tail: String, interval: Int?): String? {
        val names =
            tail
                .removePrefix("week on ")
                .removePrefix("weeks on ")
                .removePrefix("on ")
                .split(',', '&')
                .flatMap { it.split(" and ") }
                .map { it.trim().removeSuffix("s").trim() }
                .filter { it.isNotEmpty() }
        if (names.isEmpty()) return null

        val codes = names.map { DAYS[it] ?: DAYS[it + "s"] ?: return null }
        return rule("WEEKLY", interval, "BYDAY=${codes.distinct().joinToString(",")}")
    }

    /** "27th" for a day of the month, or "jan 27" for a day of the year. */
    private fun byMonthDay(tail: String, interval: Int?): String? {
        DAY_OF_MONTH.matchEntire(tail)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return null
            if (day !in 1..31) return null
            return rule("MONTHLY", interval, "BYMONTHDAY=$day")
        }

        DAY_OF_YEAR.matchEntire(tail)?.let { match ->
            val month = MONTHS.indexOfFirst { it.startsWith(match.groupValues[1]) }
            val day = match.groupValues[2].toIntOrNull() ?: return null
            if (month < 0 || day !in 1..31) return null
            return rule("YEARLY", interval, "BYMONTH=${month + 1};BYMONTHDAY=$day")
        }

        return null
    }

    private fun rule(frequency: String, interval: Int?, extra: String? = null): String =
        buildString {
            append("FREQ=").append(frequency)
            if (interval != null && interval > 1) append(";INTERVAL=").append(interval)
            extra?.let { append(';').append(it) }
        }

    private val INTERVAL = Regex("""\b(\d+)\s+(?=day|week|month|year)""")
    private val DAY_OF_MONTH = Regex("""(\d{1,2})(?:st|nd|rd|th)?""")
    private val DAY_OF_YEAR = Regex("""([a-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?""")
}
