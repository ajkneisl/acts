package dev.ajkneisl.acts

import dev.ajkneisl.acts.sync.TodoistRecurrence
import dev.ajkneisl.acts.todoist.models.TodoistDue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TodoistRecurrenceTest {

    private fun rule(phrase: String, lang: String? = "en"): String? =
        TodoistRecurrence.toRRule(
            TodoistDue(date = "2026-09-10", string = phrase, lang = lang, isRecurring = true)
        )

    @Test
    fun `simple frequencies`() {
        assertEquals("FREQ=DAILY", rule("every day"))
        assertEquals("FREQ=WEEKLY", rule("every week"))
        assertEquals("FREQ=MONTHLY", rule("every month"))
        assertEquals("FREQ=YEARLY", rule("every year"))
    }

    @Test
    fun `numeric intervals`() {
        assertEquals("FREQ=DAILY;INTERVAL=3", rule("every 3 days"))
        assertEquals("FREQ=WEEKLY;INTERVAL=2", rule("every 2 weeks"))
        assertEquals("FREQ=MONTHLY;INTERVAL=6", rule("every 6 months"))
        assertEquals("FREQ=YEARLY;INTERVAL=2", rule("every 2 years"))
        // An interval of one is the default and does not need saying.
        assertEquals("FREQ=DAILY", rule("every 1 day"))
    }

    @Test
    fun `other means an interval of two`() {
        assertEquals("FREQ=DAILY;INTERVAL=2", rule("every other day"))
        assertEquals("FREQ=WEEKLY;INTERVAL=2", rule("every other week"))
        assertEquals("FREQ=MONTHLY;INTERVAL=2", rule("every other month"))
    }

    @Test
    fun `single weekdays, long and short`() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO", rule("every monday"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO", rule("every mon"))
        assertEquals("FREQ=WEEKLY;BYDAY=TH", rule("every thursday"))
        assertEquals("FREQ=WEEKLY;BYDAY=TH", rule("every thurs"))
        assertEquals("FREQ=WEEKLY;BYDAY=SU", rule("every sunday"))
    }

    @Test
    fun `several weekdays, however they are separated`() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", rule("every mon, wed, fri"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", rule("every monday, wednesday, friday"))
        assertEquals("FREQ=WEEKLY;BYDAY=TU,TH", rule("every tue & thu"))
        assertEquals("FREQ=WEEKLY;BYDAY=SA,SU", rule("every saturday and sunday"))
    }

    @Test
    fun `plural weekdays`() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO", rule("every mondays"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,FR", rule("every mondays, fridays"))
    }

    @Test
    fun `weekdays as a set`() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", rule("every weekday"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", rule("every workday"))
    }

    @Test
    fun `an interval combined with weekdays`() {
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO", rule("every 2 weeks on mon"))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR", rule("every other week on mon, fri"))
    }

    @Test
    fun `days of the month and year`() {
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=27", rule("every 27th"))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=1", rule("every 1st"))
        assertEquals("FREQ=YEARLY;BYMONTH=1;BYMONTHDAY=27", rule("every jan 27"))
        assertEquals("FREQ=YEARLY;BYMONTH=12;BYMONTHDAY=25", rule("every december 25th"))
    }

    @Test
    fun `times of day are a time, not a pattern`() {
        assertEquals("FREQ=DAILY", rule("every day at 10am"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO", rule("every monday at 09:00"))
        assertEquals("FREQ=DAILY", rule("every morning"))
        assertEquals("FREQ=DAILY", rule("every evening"))
    }

    @Test
    fun `trailing clauses are ignored`() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO", rule("every monday starting jan 5"))
        assertEquals("FREQ=DAILY", rule("every day until dec 31"))
    }

    @Test
    fun `completion-based recurrence cannot be expressed`() {
        // "every!" repeats from the completion date, so the next occurrence depends on an action
        // that has not happened. An RRULE would place occurrences that are simply wrong.
        assertNull(rule("every! 3 days"))
        assertNull(rule("every! monday"))
    }

    @Test
    fun `anything unrecognised gives up rather than guessing`() {
        assertNull(rule("every last day of the month"))
        assertNull(rule("every 3rd friday"))
        assertNull(rule("every quarter"))
        assertNull(rule("whenever I feel like it"))
    }

    @Test
    fun `other languages are left alone`() {
        assertNull(rule("cada dia", lang = "es"))
        assertNull(rule("jeden tag", lang = "de"))
    }

    @Test
    fun `a task that does not repeat has no rule`() {
        assertNull(
            TodoistRecurrence.toRRule(
                TodoistDue(date = "2026-09-10", string = "sep 10", isRecurring = false)
            )
        )
        assertNull(TodoistRecurrence.toRRule(null))
    }
}
