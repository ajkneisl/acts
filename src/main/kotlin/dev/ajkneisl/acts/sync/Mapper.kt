package dev.ajkneisl.acts.sync

import dev.ajkneisl.acts.config.Setting
import dev.ajkneisl.acts.config.Settings
import dev.ajkneisl.acts.ical.models.EventTime
import dev.ajkneisl.acts.ical.models.IcsEvent
import dev.ajkneisl.acts.todoist.models.TaskPatch
import dev.ajkneisl.acts.todoist.models.TodoistTask
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** Maps a Todoist task to the event that represents it, and back. */
object Mapper {

    /** Everything after this in a description is ours, not the user's. */
    const val FOOTER = "\n-- Todoist --\n"

    fun uidFor(taskId: String): String = "todoist-$taskId@acts.ajkneisl.dev"

    /** Todoist stores 4 = P1 (urgent) down to 1 = P4 (none). */
    fun priorityLabel(priority: Int): String? = when (priority) {
        4 -> "P1"
        3 -> "P2"
        2 -> "P3"
        else -> null
    }

    // -------------------------------------------------------- task -> calendar

    /** The event a task should have, or null when the task does not belong on the calendar. */
    fun taskToEvent(
        task: TodoistTask,
        settings: Settings,
        zone: ZoneId,
        existing: IcsEvent? = null,
    ): IcsEvent? {
        val (start, end) = timesFor(task, settings, zone) ?: return null
        return IcsEvent(
            uid = existing?.uid ?: uidFor(task.id),
            summary = task.content,
            start = start,
            end = end,
            description = decorate(task),
            taskId = task.id,
            sequence = (existing?.sequence ?: 0) + 1,
            url = task.webUrl,
            recurrenceRule = TodoistRecurrence.toRRule(task.due),
            unknownLines = existing?.unknownLines.orEmpty(),
            // Dropped: a kept override leaves a series Todoist knows nothing about.
            overrides = emptyList(),
        )
    }

    private fun timesFor(
        task: TodoistTask,
        settings: Settings,
        zone: ZoneId,
    ): Pair<EventTime, EventTime>? {
        val due = task.due ?: return null
        if (!due.hasTime) {
            val date = due.localDate() ?: return null
            // DTEND is exclusive for dates, so a one-day block ends the following day.
            return EventTime.AllDay(date) to EventTime.AllDay(date.plusDays(1))
        }
        val startInstant = due.instant(zone) ?: return null
        val minutes = task.duration?.minutes()?.takeIf { it > 0 } ?: settings.int(Setting.DEFAULT_DURATION_MINUTES)
        return EventTime.Timed(startInstant) to
            EventTime.Timed(startInstant.plus(Duration.ofMinutes(minutes.toLong())))
    }

    private fun decorate(task: TodoistTask): String {
        val extras = buildList {
            priorityLabel(task.priority)?.let { add("Priority: $it") }
            if (task.labels.isNotEmpty()) add("Labels: " + task.labels.joinToString(", "))
            add(task.webUrl)
        }
        return task.description.trimEnd() + FOOTER + extras.joinToString("\n")
    }

    /** Removes our footer so we recover exactly what the user typed. */
    fun stripFooter(description: String): String =
        description.substringBefore(FOOTER).trimEnd()

    // -------------------------------------------------------- calendar -> task

    /** The Todoist-side change implied by an event. Never call this for a recurring task. */
    fun eventToPatch(event: IcsEvent): TaskPatch {
        val description = stripFooter(event.description)
        return when (val start = event.start) {
            is EventTime.AllDay -> TaskPatch(
                content = event.summary,
                description = description,
                dueDate = start.date,
            )

            is EventTime.Timed -> {
                val end = (event.end as? EventTime.Timed)?.instant
                val minutes = end
                    ?.let { Duration.between(start.instant, it).toMinutes().toInt() }
                    ?.takeIf { it > 0 }
                TaskPatch(
                    content = event.summary,
                    description = description,
                    dueDatetime = start.instant,
                    durationMinutes = minutes,
                )
            }
        }
    }

    // ------------------------------------------------------------ fingerprints

    /** Digest of the fields both sides share, so `updated_at` churn alone is not a change. */
    fun fingerprintTask(task: TodoistTask, settings: Settings, zone: ZoneId): String? {
        val (start, end) = timesFor(task, settings, zone) ?: return null
        // No overrides, so an event that has one never matches and gets rewritten.
        return digest(
            task.content,
            decorate(task),
            start,
            end,
            TodoistRecurrence.toRRule(task.due),
            emptyList(),
        )
    }

    fun fingerprintEvent(event: IcsEvent): String =
        digest(
            event.summary,
            event.description,
            event.start,
            event.end,
            event.recurrenceRule,
            event.overrides,
        )

    private fun digest(
        summary: String,
        description: String,
        start: EventTime,
        end: EventTime,
        recurrence: String?,
        overrides: List<String>,
    ): String {
        val canonical = listOf(
            summary.trim(),
            description.trim(),
            render(start),
            render(end),
            recurrence.orEmpty(),
            overrides.joinToString("\u0000"),
            // NUL: a separator that cannot appear inside any field above.
        ).joinToString("\u0000")
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun render(time: EventTime): String = when (time) {
        is EventTime.AllDay -> "D:" + time.date
        // Seconds only: Apple and Todoist disagree below that.
        is EventTime.Timed -> "T:" + time.instant.epochSecond
    }

    // ----------------------------------------------------------------- scoping

    /** Whether a task is close enough to now to deserve a calendar block. */
    fun inWindow(task: TodoistTask, settings: Settings, today: LocalDate): Boolean {
        val date = task.due?.localDate() ?: return false
        return !date.isBefore(today.minusDays(settings.long(Setting.PAST_DAYS))) &&
            !date.isAfter(today.plusDays(settings.long(Setting.FUTURE_DAYS)))
    }
}
