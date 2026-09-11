package dev.ajkneisl.acts.sync

import dev.ajkneisl.acts.config.ConflictPolicy
import dev.ajkneisl.acts.config.DeletedEventPolicy
import dev.ajkneisl.acts.config.Setting
import dev.ajkneisl.acts.config.Settings
import dev.ajkneisl.acts.ical.Ics
import dev.ajkneisl.acts.ical.models.EventTime
import dev.ajkneisl.acts.ical.models.IcsEvent
import dev.ajkneisl.acts.sync.models.ActionKind
import dev.ajkneisl.acts.sync.models.Link
import dev.ajkneisl.acts.sync.models.SyncAction
import dev.ajkneisl.acts.sync.models.SyncReport
import dev.ajkneisl.acts.todoist.models.TodoistTask
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.slf4j.LoggerFactory

/** Reconciles Todoist tasks against the events on the calendar. */
class SyncEngine(
    private val tasks: TaskSide,
    private val calendar: CalendarSide,
    private val settings: Settings,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val log = LoggerFactory.getLogger(SyncEngine::class.java)

    /** The calendar's change token. One small request, and it never touches Todoist. */
    fun calendarToken(): String? = runCatching { calendar.collectionToken() }.getOrNull()

    /**
     * @param knownToken a collection token the caller has just read, to save re-reading it. Must
     *   have been read before any of this pass's writes, or the pass would record a state it never
     *   observed.
     */
    fun sync(previous: SyncState, dryRun: Boolean = false, knownToken: String? = null): SyncReport {
        val actions = mutableListOf<SyncAction>()
        var state = previous.copy(calendarUrl = calendar.calendarUrl)
        val now = Instant.now(clock)
        val today = LocalDate.now(clock.withZone(zone))

        val allTasks = tasks.listTasks()
        val tasksById = allTasks.associateBy { it.id }
        val inScope = allTasks.filter {
            !it.checked && !it.isDeleted && Mapper.inWindow(it, settings, today)
        }

        // One cheap request: if nothing moved on either side, skip the fetch entirely.
        val tokenBefore = knownToken ?: runCatching { calendar.collectionToken() }.getOrNull()
        if (!dryRun && canSkip(previous, tokenBefore, inScope)) {
            log.debug("Calendar ctag and every task unchanged; skipping the event fetch")
            return SyncReport(emptyList(), state.copy(calendarCtag = tokenBefore))
        }

        val events =
            calendar.listEvents().mapNotNull { obj ->
                val parsed = obj.data?.let { Ics.parse(it, zone) } ?: return@mapNotNull null
                ParsedEvent(href = obj.href, etag = obj.etag, event = parsed)
            }
        val eventsByTaskId = events.filter { it.taskId != null }.associateBy { it.taskId!! }

        log.info(
            "Todoist: {} task(s) in scope of {} total; calendar: {} event(s)",
            inScope.size,
            allTasks.size,
            events.size,
        )

        // ---- pass 1: every task that belongs on the calendar -------------------
        for (task in inScope) {
            val link = state.links[task.id]
            val existing = eventsByTaskId[task.id]
            state = reconcileTask(task, link, existing, state, actions, dryRun, now)
        }

        // ---- pass 2: events with no in-scope task ------------------------------
        // One request at most, and only if some event has actually lost its task.
        val completed = lazy { completedIds(now) }
        val inScopeIds = inScope.mapTo(mutableSetOf()) { it.id }
        for (parsed in events) {
            val taskId = parsed.taskId
            if (taskId != null && taskId in inScopeIds) continue
            state = reconcileOrphanEvent(parsed, tasksById, completed, state, actions, dryRun, now)
        }

        // The token for the state we looked at, never one re-read after our own writes:
        // a later token would also cover edits that landed mid-pass and silently skip them.
        return SyncReport(actions, state.copy(calendarCtag = tokenBefore))
    }

    /** Whether this pass can be answered without fetching the calendar. */
    private fun canSkip(previous: SyncState, token: String?, inScope: List<TodoistTask>): Boolean {
        // No baseline (first run), or a backend with no collection token: do the work.
        if (token == null || previous.calendarCtag == null) return false
        if (token != previous.calendarCtag) return false

        val links = previous.links
        // A task we have never linked, or one whose updated_at moved.
        if (inScope.any { links[it.id]?.todoistUpdatedAt != it.updatedAt }) return false
        // A link whose task completed, was deleted, or fell out of the window.
        val inScopeIds = inScope.mapTo(mutableSetOf()) { it.id }
        return links.keys.none { it !in inScopeIds }
    }

    // ------------------------------------------------------------------ pass 1

    private fun reconcileTask(
        task: TodoistTask,
        link: Link?,
        existing: ParsedEvent?,
        stateIn: SyncState,
        actions: MutableList<SyncAction>,
        dryRun: Boolean,
        now: Instant,
    ): SyncState {
        var state = stateIn
        val desired = Mapper.taskToEvent(task, settings, zone, existing?.event)
        val taskFingerprint = Mapper.fingerprintTask(task, settings, zone)

        if (desired == null || taskFingerprint == null) {
            // The task lost its due date, so it no longer belongs on the calendar.
            if (existing != null) {
                actions += SyncAction(ActionKind.DELETE_EVENT, task.content, "due date removed")
                if (!dryRun) calendar.delete(existing.href, existing.etag)
            }
            return state.withoutLink(task.id)
        }

        if (existing == null) {
            return handleMissingEvent(
                task,
                link,
                desired,
                taskFingerprint,
                state,
                actions,
                dryRun,
                now,
            )
        }

        val eventFingerprint = Mapper.fingerprintEvent(existing.event)
        if (taskFingerprint == eventFingerprint) {
            // Already agree. Refresh the tokens so the next pass starts from a clean baseline.
            return state.withLink(
                Link(
                    taskId = task.id,
                    href = existing.href,
                    etag = existing.etag,
                    todoistUpdatedAt = task.updatedAt,
                    fingerprint = taskFingerprint,
                    lastSyncedAt = now.toString(),
                )
            )
        }

        val todoistChanged = link == null || task.updatedAt != link.todoistUpdatedAt
        val calendarChanged = link == null || existing.etag != link.etag

        val direction =
            when {
                // The done mark is ours, not something the user typed: a task that is open again
                // has to lose it, whatever the conflict policy would otherwise say about a
                // summary that changed on the calendar side.
                Mapper.isMarkedDone(existing.event) -> Direction.TO_CALENDAR

                // A repeating task's event is a read-only projection. Todoist gives recurrence
                // only as a phrase plus the next date, and writing a date back would replace the
                // whole due specification and destroy the repeat -- so a calendar edit can never
                // be accepted. Reverting it is the only coherent answer: skipping would leave the
                // calendar showing a series Todoist knows nothing about, permanently.
                task.isRecurring -> {
                    if (calendarChanged) {
                        actions +=
                            SyncAction(
                                ActionKind.CONFLICT,
                                task.content,
                                "repeating task: reverting the calendar edit, " +
                                    "these are managed from Todoist",
                            )
                    }
                    Direction.TO_CALENDAR
                }

                todoistChanged && !calendarChanged -> Direction.TO_CALENDAR
                calendarChanged && !todoistChanged -> Direction.TO_TODOIST
                // Both moved, or we have no baseline at all: fall back to policy.
                else ->
                    when (settings.enum<ConflictPolicy>(Setting.CONFLICT_POLICY)) {
                        ConflictPolicy.TODOIST_WINS -> Direction.TO_CALENDAR
                        ConflictPolicy.CALENDAR_WINS -> Direction.TO_TODOIST
                        ConflictPolicy.NEWEST_WINS -> newestWins(task, existing.event)
                    }.also {
                        if (todoistChanged && calendarChanged) {
                            actions +=
                                SyncAction(
                                    ActionKind.CONFLICT,
                                    task.content,
                                    "changed on both sides, resolved by ${settings.enum<ConflictPolicy>(Setting.CONFLICT_POLICY)}",
                                )
                        }
                    }
            }

        return when (direction) {
            Direction.TO_CALENDAR ->
                pushToCalendar(
                    task,
                    desired,
                    existing,
                    taskFingerprint,
                    state,
                    actions,
                    dryRun,
                    now,
                )

            Direction.TO_TODOIST -> pushToTodoist(task, existing, state, actions, dryRun, now)
        }
    }

    private fun handleMissingEvent(
        task: TodoistTask,
        link: Link?,
        desired: IcsEvent,
        taskFingerprint: String,
        state: SyncState,
        actions: MutableList<SyncAction>,
        dryRun: Boolean,
        now: Instant,
    ): SyncState {
        if (link == null) {
            // Genuinely new on the Todoist side.
            return createEvent(task, desired, taskFingerprint, state, actions, dryRun, now)
        }

        // We had an event and it is gone, so somebody deleted it in Calendar.app.
        return when (settings.enum<DeletedEventPolicy>(Setting.ON_EVENT_DELETED)) {
            DeletedEventPolicy.RECREATE ->
                createEvent(task, desired, taskFingerprint, state, actions, dryRun, now)

            DeletedEventPolicy.COMPLETE_TASK -> {
                actions +=
                    SyncAction(ActionKind.COMPLETE_TASK, task.content, "its event was deleted")
                if (!dryRun) tasks.close(task.id)
                state.withoutLink(task.id)
            }

            DeletedEventPolicy.SUPPRESS -> {
                // Stay out of the way until the task itself changes; then the block earns its
                // place again and we re-create it.
                if (link.suppressed && link.fingerprint == taskFingerprint) {
                    state
                } else if (link.fingerprint == taskFingerprint) {
                    actions +=
                        SyncAction(
                            ActionKind.SKIPPED,
                            task.content,
                            "event deleted in Calendar; not re-creating until the task changes",
                        )
                    state.withLink(link.copy(suppressed = true, etag = null))
                } else {
                    createEvent(task, desired, taskFingerprint, state, actions, dryRun, now)
                }
            }
        }
    }

    private fun createEvent(
        task: TodoistTask,
        desired: IcsEvent,
        fingerprint: String,
        state: SyncState,
        actions: MutableList<SyncAction>,
        dryRun: Boolean,
        now: Instant,
    ): SyncState {
        actions += SyncAction(ActionKind.CREATE_EVENT, task.content, describe(desired))
        if (dryRun) return state

        val written =
            try {
                calendar.create(task.id, Ics.render(desired, now))
            } catch (e: SyncConflictException) {
                // An event is already there; adopt it on the next pass rather than clobber it.
                log.warn("Event for task {} already exists, adopting next pass", task.id)
                actions += SyncAction(ActionKind.SKIPPED, task.content, "event already exists")
                return state
            }
        return state.withLink(
            Link(
                taskId = task.id,
                href = written.href,
                etag = written.etag,
                todoistUpdatedAt = task.updatedAt,
                fingerprint = fingerprint,
                lastSyncedAt = now.toString(),
            )
        )
    }

    private fun pushToCalendar(
        task: TodoistTask,
        desired: IcsEvent,
        existing: ParsedEvent,
        fingerprint: String,
        state: SyncState,
        actions: MutableList<SyncAction>,
        dryRun: Boolean,
        now: Instant,
    ): SyncState {
        actions += SyncAction(ActionKind.UPDATE_EVENT, task.content, describe(desired))
        if (dryRun) return state

        val written =
            try {
                calendar.update(existing.href, Ics.render(desired, now), ifMatch = existing.etag)
            } catch (e: SyncConflictException) {
                // The calendar moved under us mid-pass. Leave it; the next pass sees both changes
                // and
                // resolves them properly instead of overwriting an edit we never read.
                actions +=
                    SyncAction(
                        ActionKind.SKIPPED,
                        task.content,
                        "calendar changed mid-sync, retrying next pass",
                    )
                return state
            }
        return state.withLink(
            Link(
                taskId = task.id,
                href = written.href,
                etag = written.etag,
                todoistUpdatedAt = task.updatedAt,
                fingerprint = fingerprint,
                lastSyncedAt = now.toString(),
            )
        )
    }

    private fun pushToTodoist(
        task: TodoistTask,
        existing: ParsedEvent,
        state: SyncState,
        actions: MutableList<SyncAction>,
        dryRun: Boolean,
        now: Instant,
    ): SyncState {
        if (task.isRecurring) {
            // Writing a due date to a recurring task replaces its whole due specification and
            // destroys the recurrence, so a calendar drag is never worth that trade.
            actions +=
                SyncAction(
                    ActionKind.SKIPPED,
                    task.content,
                    "recurring task: calendar edits are not written back (it would drop the repeat rule)",
                )
            return state
        }

        val patch = Mapper.eventToPatch(existing.event)
        actions += SyncAction(ActionKind.UPDATE_TASK, task.content, describe(existing.event))
        if (dryRun) return state

        val updated = tasks.update(task.id, patch)
        return state.withLink(
            Link(
                taskId = task.id,
                href = existing.href,
                etag = existing.etag,
                todoistUpdatedAt = updated.updatedAt,
                fingerprint = Mapper.fingerprintEvent(existing.event),
                lastSyncedAt = now.toString(),
            )
        )
    }

    private fun newestWins(task: TodoistTask, event: IcsEvent): Direction {
        val taskAt = task.updatedAtInstant()
        val eventAt = event.lastModified
        return when {
            taskAt == null -> Direction.TO_TODOIST
            eventAt == null -> Direction.TO_CALENDAR
            taskAt.isAfter(eventAt) -> Direction.TO_CALENDAR
            else -> Direction.TO_TODOIST
        }
    }

    // ------------------------------------------------------------------ pass 2

    private fun reconcileOrphanEvent(
        parsed: ParsedEvent,
        tasksById: Map<String, TodoistTask>,
        completed: Lazy<Set<String>?>,
        stateIn: SyncState,
        actions: MutableList<SyncAction>,
        dryRun: Boolean,
        now: Instant,
    ): SyncState {
        var state = stateIn
        val taskId = parsed.taskId

        if (taskId != null) {
            // Already marked: the block is a record of something finished, and there is nothing
            // left to reconcile. Bailing out here also spares us asking Todoist about the same
            // long-done task on every single pass for as long as the event lives.
            if (Mapper.isMarkedDone(parsed.event)) return state.withoutLink(taskId)

            // Ours, but the task is completed, deleted, or has left the sync window. A completed
            // task is simply absent from the active list, so absence alone does not say which.
            val task = tasksById[taskId]
            val isDone =
                when {
                    task != null -> task.checked && !task.isDeleted
                    else -> completed.value?.contains(taskId)
                }

            if (isDone == true) return markDone(taskId, parsed, state, actions, dryRun, now)
            if (isDone == null) {
                // Todoist would not tell us which it was. Deleting on a guess would throw away a
                // block we were asked to keep, so leave it exactly as it is and try again later.
                actions +=
                    SyncAction(
                        ActionKind.SKIPPED,
                        parsed.event.summary,
                        "cannot tell whether the task was completed or deleted; leaving the event",
                    )
                return state
            }

            val reason =
                when {
                    task == null -> "task no longer exists"
                    task.isDeleted -> "task deleted"
                    else -> "task left the sync window"
                }
            actions += SyncAction(ActionKind.DELETE_EVENT, parsed.event.summary, reason)
            if (!dryRun) calendar.delete(parsed.href, parsed.etag)
            return state.withoutLink(taskId)
        }

        // Not ours: somebody created this event on the managed calendar by hand.
        val uid = parsed.event.uid
        if (parsed.event.recurrenceRule != null) {
            if (uid !in state.ignoredEventUids) {
                actions +=
                    SyncAction(
                        ActionKind.SKIPPED,
                        parsed.event.summary,
                        "recurring event: Todoist cannot represent an RRULE as a task",
                    )
                state = state.copy(ignoredEventUids = state.ignoredEventUids + uid)
            }
            return state
        }
        if (!settings.bool(Setting.CREATE_TASKS_FROM_EVENTS)) {
            if (uid !in state.ignoredEventUids) {
                actions +=
                    SyncAction(
                        ActionKind.SKIPPED,
                        parsed.event.summary,
                        "createTasksFromEvents is off",
                    )
                state = state.copy(ignoredEventUids = state.ignoredEventUids + uid)
            }
            return state
        }

        val patch = Mapper.eventToPatch(parsed.event)
        actions += SyncAction(ActionKind.CREATE_TASK, parsed.event.summary, describe(parsed.event))
        if (dryRun) return state

        val created =
            tasks.create(
                content = parsed.event.summary.ifBlank { "(untitled event)" },
                description = Mapper.stripFooter(parsed.event.description),
                projectId = settings[Setting.NEW_TASK_PROJECT_ID],
                patch = patch,
            )

        // Stamp the event so the next pass recognises the pairing instead of creating a
        // second task from the same event.
        val stamped =
            parsed.event.copy(
                taskId = created.id,
                url = created.webUrl,
                sequence = parsed.event.sequence + 1,
            )
        val written =
            try {
                calendar.update(parsed.href, Ics.render(stamped, now), ifMatch = parsed.etag)
            } catch (e: SyncConflictException) {
                log.warn(
                    "Could not stamp {} with task id {}; next pass will retry",
                    parsed.href,
                    created.id,
                )
                null
            }

        return state.withLink(
            Link(
                taskId = created.id,
                href = written?.href ?: parsed.href,
                etag = written?.etag,
                todoistUpdatedAt = created.updatedAt,
                fingerprint = Mapper.fingerprintEvent(stamped),
                lastSyncedAt = now.toString(),
            )
        )
    }

    /** Keeps the block and marks it done, rather than deleting the evidence of a finished task. */
    private fun markDone(
        taskId: String,
        parsed: ParsedEvent,
        state: SyncState,
        actions: MutableList<SyncAction>,
        dryRun: Boolean,
        now: Instant,
    ): SyncState {
        actions += SyncAction(ActionKind.UPDATE_EVENT, parsed.event.summary, "task completed")
        if (dryRun) return state

        try {
            calendar.update(
                parsed.href,
                Ics.render(Mapper.markDone(parsed.event), now),
                ifMatch = parsed.etag,
            )
        } catch (e: SyncConflictException) {
            // Somebody touched the event mid-pass. Keep the link so the next pass marks it.
            actions +=
                SyncAction(
                    ActionKind.SKIPPED,
                    parsed.event.summary,
                    "calendar changed mid-sync, retrying next pass",
                )
            return state
        }
        return state.withoutLink(taskId)
    }

    /**
     * Ids completed recently, or null when Todoist could not say. Deliberately not fatal: a sync
     * that otherwise works should not stop because this one lookup is unavailable.
     */
    private fun completedIds(now: Instant): Set<String>? =
        runCatching {
                tasks
                    .listCompleted(
                        now.minus(Duration.ofDays(COMPLETION_LOOKBACK_DAYS)),
                        // Exclusive, and clocks disagree: a task completed a second ago must land
                        // inside the window.
                        now.plus(Duration.ofDays(1)),
                    )
                    .mapTo(mutableSetOf()) { it.id }
            }
            .getOrElse {
                log.warn("Could not list completed tasks: {}", it.message)
                null
            }

    // ------------------------------------------------------------------- utils

    private enum class Direction {
        TO_CALENDAR,
        TO_TODOIST,
    }

    private data class ParsedEvent(val href: String, val etag: String?, val event: IcsEvent) {
        /** Prefers the explicit marker, falling back to our deterministic UID scheme. */
        val taskId: String?
            get() =
                event.taskId
                    // Both spellings: an event written before the rename still has to match,
                    // or it reads as hand-made and earns itself a duplicate task.
                    ?: Regex("^todoist-([^@]+)@(acts|applecaldoist)")
                        .find(event.uid)
                        ?.groupValues
                        ?.get(1)
    }

    private fun describe(event: IcsEvent): String =
        when (val start = event.start) {
            is EventTime.AllDay -> "all day ${start.date}"
            is EventTime.Timed ->
                start.instant.atZone(zone).toLocalDateTime().toString().replace('T', ' ')
        }

    private companion object {
        /** How far back to look for a completion. Long enough to cover a daemon that was down. */
        const val COMPLETION_LOOKBACK_DAYS = 14L
    }
}
