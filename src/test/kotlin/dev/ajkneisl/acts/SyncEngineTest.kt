package dev.ajkneisl.acts

import dev.ajkneisl.acts.config.ConflictPolicy
import dev.ajkneisl.acts.config.DeletedEventPolicy
import dev.ajkneisl.acts.config.Setting
import dev.ajkneisl.acts.config.Settings
import dev.ajkneisl.acts.ical.Ics
import dev.ajkneisl.acts.sync.SyncEngine
import dev.ajkneisl.acts.sync.SyncState
import dev.ajkneisl.acts.sync.models.ActionKind
import dev.ajkneisl.acts.sync.models.SyncReport
import dev.ajkneisl.acts.todoist.models.TaskPatch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncEngineTest {

    private val zone: ZoneId = ZoneId.of("America/Chicago")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), zone)

    private fun engine(
        todoist: FakeTodoist,
        calendar: FakeCalendar,
        settings: Settings = Settings.of(),
    ) = SyncEngine(todoist, calendar, settings, zone, clock)

    private fun SyncReport.kinds(): List<ActionKind> = actions.map { it.kind }

    private fun recurringTask(id: String, content: String, due: String, phrase: String) =
        task(id, content, due).let {
            it.copy(due = it.due!!.copy(string = phrase, lang = "en", isRecurring = true))
        }

    // ------------------------------------------------------- Todoist -> Apple

    @Test
    fun `a new dated task becomes a calendar event`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship the sync", "2026-09-10T15:00:00Z", 60)))
        val calendar = FakeCalendar()

        val report = engine(todoist, calendar).sync(SyncState())

        assertEquals(listOf(ActionKind.CREATE_EVENT), report.kinds())
        assertEquals(1, calendar.objects.size)
        val ics = calendar.objects.values.single().data!!
        assertTrue(ics.contains("SUMMARY:Ship the sync"), ics)
        assertTrue(ics.contains("DTSTART:20260910T150000Z"), ics)
        assertTrue(ics.contains("DTEND:20260910T160000Z"), ics)
        assertEquals(1, report.state.links.size)
    }

    @Test
    fun `an undated task is never put on the calendar`() {
        val todoist = FakeTodoist(listOf(task("1", "Someday", null)))
        val report = engine(todoist, FakeCalendar()).sync(SyncState())
        assertTrue(report.actions.isEmpty(), report.actions.toString())
    }

    @Test
    fun `a task outside the window is not synced`() {
        val todoist = FakeTodoist(listOf(task("1", "Far future", "2027-12-01")))
        val report = engine(todoist, FakeCalendar()).sync(SyncState())
        assertTrue(report.actions.isEmpty(), report.actions.toString())
    }

    @Test
    fun `rescheduling in Todoist moves the event`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z", 60)))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())

        todoist.editExternally("1") { it.copy(due = it.due!!.copy(date = "2026-09-11T09:00:00Z")) }
        val second = engine(todoist, calendar).sync(first.state)

        assertEquals(listOf(ActionKind.UPDATE_EVENT), second.kinds())
        assertTrue(calendar.objects.values.single().data!!.contains("DTSTART:20260911T090000Z"))
    }

    @Test
    fun `completing a task removes its event`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z")))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())

        todoist.close("1")
        val second = engine(todoist, calendar).sync(first.state)

        assertEquals(listOf(ActionKind.DELETE_EVENT), second.kinds())
        assertTrue(calendar.objects.isEmpty())
        assertTrue(second.state.links.isEmpty())
    }

    @Test
    fun `clearing the due date removes the event`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z")))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())

        todoist.editExternally("1") { it.copy(due = null) }
        val second = engine(todoist, calendar).sync(first.state)

        assertEquals(listOf(ActionKind.DELETE_EVENT), second.kinds())
        assertTrue(calendar.objects.isEmpty())
    }

    // ------------------------------------------------------- Apple -> Todoist

    @Test
    fun `dragging the event in Calendar reschedules the task`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z", 60)))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())

        val href = calendar.objects.keys.single()
        calendar.editExternally(href) {
            it.replace("DTSTART:20260910T150000Z", "DTSTART:20260910T190000Z")
                .replace("DTEND:20260910T160000Z", "DTEND:20260910T200000Z")
        }
        val second = engine(todoist, calendar).sync(first.state)

        assertEquals(listOf(ActionKind.UPDATE_TASK), second.kinds())
        assertEquals("2026-09-10T19:00:00Z", todoist.tasks.getValue("1").due!!.date)
        assertEquals(60, todoist.tasks.getValue("1").duration!!.minutes())
    }

    @Test
    fun `renaming the event renames the task`() {
        val todoist = FakeTodoist(listOf(task("1", "Old name", "2026-09-10T15:00:00Z")))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())

        calendar.editExternally(calendar.objects.keys.single()) {
            it.replace("SUMMARY:Old name", "SUMMARY:New name")
        }
        val second = engine(todoist, calendar).sync(first.state)

        assertEquals(listOf(ActionKind.UPDATE_TASK), second.kinds())
        assertEquals("New name", todoist.tasks.getValue("1").content)
    }

    @Test
    fun `an event created by hand becomes a task and gets stamped`() {
        val todoist = FakeTodoist()
        val calendar = FakeCalendar()
        val href = calendar.calendarUrl + "handmade.ics"
        calendar.update(
            href,
            """
                BEGIN:VCALENDAR
                BEGIN:VEVENT
                UID:handmade-1
                SUMMARY:Dentist
                DTSTART:20260912T140000Z
                DTEND:20260912T150000Z
                END:VEVENT
                END:VCALENDAR
            """.trimIndent(),
            null,
        )

        val report = engine(todoist, calendar).sync(SyncState())

        assertEquals(listOf(ActionKind.CREATE_TASK), report.kinds())
        val created = todoist.tasks.values.single()
        assertEquals("Dentist", created.content)
        assertEquals("2026-09-12T14:00:00Z", created.due!!.date)
        // The stamp is what stops the next pass creating a duplicate task.
        assertTrue(calendar.objects.getValue(href).data!!.contains("${Ics.TASK_ID_PROPERTY}:${created.id}"))
    }

    @Test
    fun `a handmade event is not turned into a second task on the next pass`() {
        val todoist = FakeTodoist()
        val calendar = FakeCalendar()
        calendar.update(
            calendar.calendarUrl + "handmade.ics",
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:h1\r\nSUMMARY:Dentist\r\n" +
                "DTSTART:20260912T140000Z\r\nDTEND:20260912T150000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
            null,
        )
        val first = engine(todoist, calendar).sync(SyncState())
        val second = engine(todoist, calendar).sync(first.state)

        assertEquals(1, todoist.tasks.size)
        assertTrue(second.actions.none { it.kind == ActionKind.CREATE_TASK }, second.actions.toString())
    }

    @Test
    fun `a recurring event is refused rather than flattened into a task`() {
        val todoist = FakeTodoist()
        val calendar = FakeCalendar()
        calendar.update(
            calendar.calendarUrl + "standup.ics",
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:s1\r\nSUMMARY:Standup\r\n" +
                "DTSTART:20260910T140000Z\r\nDTEND:20260910T141500Z\r\n" +
                "RRULE:FREQ=WEEKLY\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
            null,
        )
        val report = engine(todoist, calendar).sync(SyncState())

        assertEquals(listOf(ActionKind.SKIPPED), report.kinds())
        assertTrue(todoist.tasks.isEmpty())
    }

    // ------------------------------------------------------------- conflicts

    @Test
    fun `a change on both sides is resolved by the policy`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z", 60)))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())

        todoist.editExternally("1") { it.copy(content = "Ship from Todoist") }
        calendar.editExternally(calendar.objects.keys.single()) {
            it.replace("SUMMARY:Ship", "SUMMARY:Ship from Calendar")
        }

        val report = engine(todoist, calendar, Settings.of(Setting.CONFLICT_POLICY to "TODOIST_WINS"))
            .sync(first.state)

        assertTrue(report.actions.any { it.kind == ActionKind.CONFLICT }, report.actions.toString())
        assertTrue(report.actions.any { it.kind == ActionKind.UPDATE_EVENT })
        assertEquals("Ship from Todoist", todoist.tasks.getValue("1").content)
        assertTrue(calendar.objects.values.single().data!!.contains("SUMMARY:Ship from Todoist"))
    }

    @Test
    fun `calendar wins policy sends the conflict the other way`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z", 60)))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())

        todoist.editExternally("1") { it.copy(content = "Ship from Todoist") }
        calendar.editExternally(calendar.objects.keys.single()) {
            it.replace("SUMMARY:Ship", "SUMMARY:Ship from Calendar")
        }

        val report = engine(todoist, calendar, Settings.of(Setting.CONFLICT_POLICY to "CALENDAR_WINS"))
            .sync(first.state)

        assertTrue(report.actions.any { it.kind == ActionKind.UPDATE_TASK })
        assertEquals("Ship from Calendar", todoist.tasks.getValue("1").content)
    }

    @Test
    fun `a calendar edit to a recurring task is reverted, never written back`() {
        // Writing a due date to a recurring task would replace its whole due spec and drop the
        // repeat rule. Skipping is not enough either: the calendar would keep showing an edit
        // Todoist knows nothing about. The event is a projection, so it gets rewritten.
        val todoist =
            FakeTodoist(listOf(recurringTask("1", "Water plants", "2026-09-10T15:00:00Z", "every day")))
        val calendar = FakeCalendar()
        var state = engine(todoist, calendar).sync(SyncState()).state
        state = engine(todoist, calendar).sync(state).state

        calendar.editExternally(calendar.objects.keys.single()) {
            it.replace("DTSTART:20260910T150000Z", "DTSTART:20260910T190000Z")
        }
        val second = engine(todoist, calendar).sync(state)

        assertTrue(second.actions.any { it.kind == ActionKind.UPDATE_EVENT }, second.actions.toString())
        // Todoist is untouched, and its repeat rule survives.
        assertEquals("2026-09-10T15:00:00Z", todoist.tasks.getValue("1").due!!.date)
        assertTrue(todoist.tasks.getValue("1").due!!.isRecurring)
        // And the calendar is back to matching it.
        assertTrue(calendar.objects.values.single().data!!.contains("DTSTART:20260910T150000Z"))
    }

    @Test
    fun `an occurrence dragged in Calendar is removed again`() {
        // This is what produced "multiple events": Apple stores the dragged occurrence as a
        // second VEVENT, which used to be invisible to the fingerprint and kept across rewrites.
        val todoist =
            FakeTodoist(listOf(recurringTask("1", "Standup", "2026-09-10T15:00:00Z", "every monday")))
        val calendar = FakeCalendar()
        var state = engine(todoist, calendar).sync(SyncState()).state
        state = engine(todoist, calendar).sync(state).state

        val href = calendar.objects.keys.single()
        calendar.editExternally(href) {
            it.replace(
                "END:VCALENDAR",
                "BEGIN:VEVENT\r\nUID:x\r\nRECURRENCE-ID:20260914T150000Z\r\n" +
                    "DTSTART:20260914T190000Z\r\nDTEND:20260914T200000Z\r\n" +
                    "SUMMARY:Standup\r\nEND:VEVENT\r\nEND:VCALENDAR",
            )
        }

        val report = engine(todoist, calendar).sync(state)

        assertTrue(report.actions.any { it.kind == ActionKind.UPDATE_EVENT }, report.actions.toString())
        val ics = calendar.objects.getValue(href).data!!
        assertTrue(!ics.contains("RECURRENCE-ID"), "the stray occurrence should have been removed")
        assertEquals(1, Regex("BEGIN:VEVENT").findAll(ics).count(), "should be a single VEVENT again")
    }

    @Test
    fun `reverting a recurring event settles rather than looping`() {
        val todoist =
            FakeTodoist(listOf(recurringTask("1", "Standup", "2026-09-10T15:00:00Z", "every monday")))
        val calendar = FakeCalendar()
        var state = engine(todoist, calendar).sync(SyncState()).state
        state = engine(todoist, calendar).sync(state).state

        calendar.editExternally(calendar.objects.keys.single()) {
            it.replace("SUMMARY:Standup", "SUMMARY:Standup, moved")
        }
        state = engine(todoist, calendar).sync(state).state
        state = engine(todoist, calendar).sync(state).state

        val quiet = engine(todoist, calendar).sync(state)
        assertTrue(quiet.actions.isEmpty(), "revert did not settle: ${quiet.actions}")
    }

    @Test
    fun `a non-recurring task still accepts calendar edits`() {
        // The one-way rule applies to repeating tasks only; everything else stays two-way.
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z", 60)))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())

        calendar.editExternally(calendar.objects.keys.single()) {
            it.replace("SUMMARY:Ship", "SUMMARY:Ship later")
        }
        val second = engine(todoist, calendar).sync(first.state)

        assertEquals(listOf(ActionKind.UPDATE_TASK), second.kinds())
        assertEquals("Ship later", todoist.tasks.getValue("1").content)
    }

    // ------------------------------------------------- deleted-event policies

    @Test
    fun `suppress leaves a deleted event deleted until the task changes`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z")))
        val calendar = FakeCalendar()
        var state = engine(todoist, calendar).sync(SyncState()).state

        calendar.clearExternally()
        state = engine(todoist, calendar).sync(state).also {
            assertEquals(listOf(ActionKind.SKIPPED), it.kinds())
        }.state
        assertTrue(calendar.objects.isEmpty())

        // A second quiet pass must stay quiet rather than re-announcing itself.
        state = engine(todoist, calendar).sync(state).also {
            assertTrue(it.actions.isEmpty(), it.actions.toString())
        }.state

        // Editing the task makes the block relevant again.
        todoist.editExternally("1") { it.copy(content = "Ship, seriously") }
        val revived = engine(todoist, calendar).sync(state)
        assertEquals(listOf(ActionKind.CREATE_EVENT), revived.kinds())
        assertEquals(1, calendar.objects.size)
    }

    @Test
    fun `recreate policy restores a deleted event immediately`() {
        val config = Settings.of(Setting.ON_EVENT_DELETED to "RECREATE")
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z")))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar, config).sync(SyncState())

        calendar.clearExternally()
        val second = engine(todoist, calendar, config).sync(first.state)

        assertEquals(listOf(ActionKind.CREATE_EVENT), second.kinds())
        assertEquals(1, calendar.objects.size)
    }

    @Test
    fun `complete-task policy closes the task when its event is deleted`() {
        val config = Settings.of(Setting.ON_EVENT_DELETED to "COMPLETE_TASK")
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z")))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar, config).sync(SyncState())

        calendar.clearExternally()
        val second = engine(todoist, calendar, config).sync(first.state)

        assertEquals(listOf(ActionKind.COMPLETE_TASK), second.kinds())
        assertTrue(todoist.tasks.getValue("1").checked)
    }

    // ---------------------------------------------------- cheap quiet passes

    @Test
    fun `a quiet pass never fetches the calendar`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z", 60)))
        val calendar = FakeCalendar()
        // Pass 1 creates the event; pass 2 re-checks because pass 1 moved the collection.
        var state = engine(todoist, calendar).sync(SyncState()).state
        state = engine(todoist, calendar).sync(state).state
        val settledFetches = calendar.listEventsCalls

        // From here nothing is changing, so the expensive REPORT must not happen at all.
        repeat(3) {
            val quiet = engine(todoist, calendar).sync(state)
            assertTrue(quiet.actions.isEmpty(), quiet.actions.toString())
            state = quiet.state
        }
        assertEquals(settledFetches, calendar.listEventsCalls, "a quiet pass fetched the calendar")
    }

    @Test
    fun `the recorded token is the state the pass observed, not the state it left behind`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z")))
        val calendar = FakeCalendar()
        val report = engine(todoist, calendar).sync(SyncState())

        // This pass created an event, so the collection has moved past what it looked at. Storing
        // the observed token is what makes the next pass re-check instead of trusting a state it
        // never saw.
        assertNotEquals(calendar.collectionToken(), report.state.calendarCtag)

        // And it does settle: once a pass writes nothing, the token stops moving.
        val settled = engine(todoist, calendar).sync(report.state)
        assertTrue(settled.actions.isEmpty(), settled.actions.toString())
        assertEquals(calendar.collectionToken(), settled.state.calendarCtag)
        val quiet = engine(todoist, calendar).sync(settled.state)
        assertEquals(0, quiet.actions.size)
    }

    @Test
    fun `a calendar change defeats the skip`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z", 60)))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())
        val afterFirst = calendar.listEventsCalls

        calendar.editExternally(calendar.objects.keys.single()) {
            it.replace("SUMMARY:Ship", "SUMMARY:Ship later")
        }
        val second = engine(todoist, calendar).sync(first.state)

        assertEquals(afterFirst + 1, calendar.listEventsCalls, "a moved ctag must force a fetch")
        assertEquals(listOf(ActionKind.UPDATE_TASK), second.kinds())
    }

    @Test
    fun `a Todoist change defeats the skip even when the calendar is untouched`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z", 60)))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())
        val afterFirst = calendar.listEventsCalls

        todoist.editExternally("1") { it.copy(content = "Ship sooner") }
        val second = engine(todoist, calendar).sync(first.state)

        assertEquals(afterFirst + 1, calendar.listEventsCalls)
        assertEquals(listOf(ActionKind.UPDATE_EVENT), second.kinds())
    }

    @Test
    fun `a new task defeats the skip`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z")))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())

        todoist.create("Second thing", "", null, TaskPatch(dueDate = LocalDate.of(2026, 9, 11)))
        val second = engine(todoist, calendar).sync(first.state)

        assertEquals(listOf(ActionKind.CREATE_EVENT), second.kinds())
    }

    @Test
    fun `a completed task defeats the skip`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z")))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())

        todoist.close("1")
        val second = engine(todoist, calendar).sync(first.state)

        assertEquals(listOf(ActionKind.DELETE_EVENT), second.kinds())
        assertTrue(calendar.objects.isEmpty())
    }

    @Test
    fun `a dry run always looks, so status reports real state`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z")))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())
        val afterFirst = calendar.listEventsCalls

        engine(todoist, calendar).sync(first.state, dryRun = true)

        assertEquals(afterFirst + 1, calendar.listEventsCalls, "dry run must not take the shortcut")
    }

    @Test
    fun `an edit landing mid-pass is not lost`() {
        // Regression: the pass must record the collection state it actually observed. Recording a
        // token read after our own writes also swallows anything that arrived in between, and the
        // next pass then skips it -- silently, and forever.
        val todoist = FakeTodoist(
            listOf(
                task("1", "First", "2026-09-10T15:00:00Z", 60),
                task("2", "Second", "2026-09-11T15:00:00Z", 60),
            )
        )
        val calendar = FakeCalendar()
        var state = engine(todoist, calendar).sync(SyncState()).state
        state = engine(todoist, calendar).sync(state).state
        assertTrue(engine(todoist, calendar).sync(state).actions.isEmpty(), "should have settled")

        val secondHref = calendar.objects.keys.first { it.contains("todoist-2") }

        // This pass writes event 1 because Todoist changed...
        todoist.editExternally("1") { it.copy(content = "First, renamed") }
        // ...while the user edits event 2 in Calendar.app mid-flight.
        calendar.onListEvents = {
            calendar.editExternally(secondHref) { it.replace("SUMMARY:Second", "SUMMARY:Second, edited") }
            calendar.onListEvents = null
        }
        val writing = engine(todoist, calendar).sync(state)
        assertTrue(writing.actions.any { it.kind == ActionKind.UPDATE_EVENT }, writing.actions.toString())
        state = writing.state

        // The edit to event 2 must still be waiting to be picked up.
        val next = engine(todoist, calendar).sync(state)
        assertEquals(listOf(ActionKind.UPDATE_TASK), next.kinds(), "the mid-pass edit was lost")
        assertEquals("Second, edited", todoist.tasks.getValue("2").content)
    }

    // -------------------------------------------------------------- timezone

    @Test
    fun `a due time with no zone is read in the sync timezone, not UTC`() {
        // Regression: in a container the host clock is UTC, so a floating 07:00 was being written
        // as 07:00Z and showing up at 02:00 in a Chicago calendar.
        val todoist = FakeTodoist(listOf(task("1", "Standup", "2026-09-11T07:00:00")))
        val calendar = FakeCalendar()

        engine(todoist, calendar).sync(SyncState())

        // 07:00 in Chicago during September (CDT, UTC-5) is 12:00Z.
        val ics = calendar.objects.values.single().data!!
        assertTrue(ics.contains("DTSTART:20260911T120000Z"), ics.lineSequence().first { "DTSTART" in it })
    }

    @Test
    fun `a due time that carries its own zone is honoured over the sync timezone`() {
        val base = task("1", "Call", "2026-09-11T07:00:00")
        val todoist = FakeTodoist(
            listOf(base.copy(due = base.due!!.copy(timezone = "Europe/London")))
        )
        val calendar = FakeCalendar()

        engine(todoist, calendar).sync(SyncState())

        // 07:00 London in September (BST, UTC+1) is 06:00Z.
        assertTrue(calendar.objects.values.single().data!!.contains("DTSTART:20260911T060000Z"))
    }

    @Test
    fun `an explicit UTC due time is left alone`() {
        val todoist = FakeTodoist(listOf(task("1", "Call", "2026-09-11T07:00:00Z")))
        val calendar = FakeCalendar()

        engine(todoist, calendar).sync(SyncState())

        assertTrue(calendar.objects.values.single().data!!.contains("DTSTART:20260911T070000Z"))
    }

    @Test
    fun `a repeating task keeps its local start time`() {
        val todoist = FakeTodoist(
            listOf(recurringTask("1", "Standup", "2026-09-11T07:00:00", "every weekday"))
        )
        val calendar = FakeCalendar()

        engine(todoist, calendar).sync(SyncState())

        val ics = calendar.objects.values.single().data!!
        assertTrue(ics.contains("DTSTART:20260911T120000Z"), ics)
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"), ics)
    }

    // ------------------------------------------------------------ recurrence

    @Test
    fun `a repeating task becomes a repeating event`() {
        val todoist = FakeTodoist(
            listOf(recurringTask("1", "Standup", "2026-09-10T15:00:00Z", "every mon, wed, fri"))
        )
        val calendar = FakeCalendar()
        engine(todoist, calendar).sync(SyncState())

        val ics = calendar.objects.values.single().data!!
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR"), ics)
    }

    @Test
    fun `a phrase we cannot express leaves a single occurrence`() {
        // "every!" repeats from completion, so any RRULE would place occurrences that are wrong.
        val todoist = FakeTodoist(
            listOf(recurringTask("1", "Water plants", "2026-09-10T15:00:00Z", "every! 3 days"))
        )
        val calendar = FakeCalendar()
        engine(todoist, calendar).sync(SyncState())

        assertTrue(!calendar.objects.values.single().data!!.contains("RRULE"))
    }

    @Test
    fun `changing the repeat rule in Todoist updates the event`() {
        val todoist = FakeTodoist(
            listOf(recurringTask("1", "Standup", "2026-09-10T15:00:00Z", "every monday"))
        )
        val calendar = FakeCalendar()
        var state = engine(todoist, calendar).sync(SyncState()).state
        state = engine(todoist, calendar).sync(state).state

        todoist.editExternally("1") { it.copy(due = it.due!!.copy(string = "every tuesday")) }
        val report = engine(todoist, calendar).sync(state)

        // Only reached if the fingerprint takes recurrence into account.
        assertEquals(listOf(ActionKind.UPDATE_EVENT), report.kinds())
        assertTrue(calendar.objects.values.single().data!!.contains("RRULE:FREQ=WEEKLY;BYDAY=TU"))
    }

    @Test
    fun `a repeating task still converges`() {
        val todoist = FakeTodoist(
            listOf(recurringTask("1", "Standup", "2026-09-10T15:00:00Z", "every weekday"))
        )
        val calendar = FakeCalendar()
        var state = engine(todoist, calendar).sync(SyncState()).state
        state = engine(todoist, calendar).sync(state).state

        val quiet = engine(todoist, calendar).sync(state)
        assertTrue(quiet.actions.isEmpty(), "recurring task did not settle: ${quiet.actions}")
    }

    // ---------------------------------------------------- legacy compatibility

    @Test
    fun `an event written before the rename is still recognised`() {
        // It carries the old X-APPLECALDOIST-TASK-ID and the old UID. If either stopped matching,
        // the event would read as hand-made and earn its task a duplicate.
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z", 60)))
        val calendar = FakeCalendar()
        calendar.update(
            calendar.calendarUrl + "todoist-1.ics",
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:todoist-1@applecaldoist.umn.app\r\n" +
                "SUMMARY:Ship\r\nDTSTART:20260910T150000Z\r\nDTEND:20260910T160000Z\r\n" +
                "X-APPLECALDOIST-TASK-ID:1\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
            null,
        )

        val report = engine(todoist, calendar).sync(SyncState())

        // Adopted and updated in place, not duplicated.
        assertTrue(report.actions.none { it.kind == ActionKind.CREATE_TASK }, report.actions.toString())
        assertEquals(1, todoist.tasks.size)
        assertEquals(1, calendar.objects.size)
    }

    @Test
    fun `a legacy event is matched by UID alone when the property is absent`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z", 60)))
        val calendar = FakeCalendar()
        calendar.update(
            calendar.calendarUrl + "todoist-1.ics",
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:todoist-1@applecaldoist.umn.app\r\n" +
                "SUMMARY:Ship\r\nDTSTART:20260910T150000Z\r\nDTEND:20260910T160000Z\r\n" +
                "END:VEVENT\r\nEND:VCALENDAR\r\n",
            null,
        )

        val report = engine(todoist, calendar).sync(SyncState())

        assertTrue(report.actions.none { it.kind == ActionKind.CREATE_TASK }, report.actions.toString())
        assertEquals(1, todoist.tasks.size)
    }

    // ------------------------------------------------------------- stability

    @Test
    fun `a second pass with no changes does nothing`() {
        val todoist = FakeTodoist(
            listOf(
                task("1", "Timed", "2026-09-10T15:00:00Z", 60, priority = 4),
                task("2", "All day", "2026-09-12", description = "notes"),
                task("3", "Labelled", "2026-09-13T09:00:00Z"),
            )
        )
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())
        assertEquals(3, first.count(ActionKind.CREATE_EVENT))

        val second = engine(todoist, calendar).sync(first.state)
        assertTrue(second.actions.isEmpty(), "expected a quiet pass, got ${second.actions}")
        assertTrue(!second.changed)
    }

    @Test
    fun `a calendar edit converges after one pass and then goes quiet`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z", 60)))
        val calendar = FakeCalendar()
        var state = engine(todoist, calendar).sync(SyncState()).state

        calendar.editExternally(calendar.objects.keys.single()) {
            it.replace("DTSTART:20260910T150000Z", "DTSTART:20260910T190000Z")
                .replace("DTEND:20260910T160000Z", "DTEND:20260910T200000Z")
        }
        state = engine(todoist, calendar).sync(state).state

        // The important property: no write-back ping-pong between the two sides.
        val third = engine(todoist, calendar).sync(state)
        assertTrue(third.actions.isEmpty(), "sync did not converge: ${third.actions}")
    }

    @Test
    fun `dry run reports the work without performing it`() {
        val todoist = FakeTodoist(listOf(task("1", "Ship", "2026-09-10T15:00:00Z")))
        val calendar = FakeCalendar()

        val report = engine(todoist, calendar).sync(SyncState(), dryRun = true)

        assertEquals(listOf(ActionKind.CREATE_EVENT), report.kinds())
        assertTrue(calendar.objects.isEmpty(), "dry run must not write")
        assertTrue(report.state.links.isEmpty(), "dry run must not record links")
    }

    @Test
    fun `all-day tasks map to exclusive-end date events`() {
        val todoist = FakeTodoist(listOf(task("1", "Pay rent", "2026-09-12")))
        val calendar = FakeCalendar()
        engine(todoist, calendar).sync(SyncState())

        val ics = calendar.objects.values.single().data!!
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20260912"), ics)
        assertTrue(ics.contains("DTEND;VALUE=DATE:20260913"), ics)
    }

    @Test
    fun `priority and link appear below the footer and never leak into the description`() {
        val todoist = FakeTodoist(listOf(task("1", "Urgent", "2026-09-10T15:00:00Z", priority = 4, description = "my notes")))
        val calendar = FakeCalendar()
        val first = engine(todoist, calendar).sync(SyncState())

        val event = assertNotNull(Ics.parse(calendar.objects.values.single().data!!))
        assertTrue(event.description.startsWith("my notes"), event.description)
        assertTrue(event.description.contains("Priority: P1"), event.description)

        // Editing only the time must not push our own footer into the Todoist description.
        calendar.editExternally(calendar.objects.keys.single()) {
            it.replace("DTSTART:20260910T150000Z", "DTSTART:20260910T170000Z")
        }
        engine(todoist, calendar).sync(first.state)
        assertEquals("my notes", todoist.tasks.getValue("1").description)
    }
}
