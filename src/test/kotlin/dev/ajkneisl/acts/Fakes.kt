package dev.ajkneisl.acts

import dev.ajkneisl.acts.caldav.models.CalendarObject
import dev.ajkneisl.acts.sync.CalendarSide
import dev.ajkneisl.acts.sync.SyncConflictException
import dev.ajkneisl.acts.sync.TaskSide
import dev.ajkneisl.acts.sync.models.WriteResult
import dev.ajkneisl.acts.todoist.models.TaskPatch
import dev.ajkneisl.acts.todoist.models.TodoistDue
import dev.ajkneisl.acts.todoist.models.TodoistDuration
import dev.ajkneisl.acts.todoist.models.TodoistTask
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** An in-memory CalDAV collection with real ETag and If-Match semantics. */
class FakeCalendar(override val calendarUrl: String = "https://example.test/cal/") : CalendarSide {
    val objects = linkedMapOf<String, CalendarObject>()
    private var etagSeq = 0

    /** Counts full-collection fetches, so tests can assert a quiet pass skipped one. */
    var listEventsCalls = 0
        private set

    /** Mirrors a CalDAV ctag: one token for the collection, bumped by any change inside it. */
    override fun collectionToken(): String = "ctag-$etagSeq"

    /** Fires after the snapshot is taken, to model an edit landing while a pass is in flight. */
    var onListEvents: (() -> Unit)? = null

    override fun listEvents(): List<CalendarObject> {
        listEventsCalls++
        val snapshot = objects.values.toList()
        onListEvents?.invoke()
        return snapshot
    }

    override fun create(taskId: String, ics: String): WriteResult {
        val url = calendarUrl + "todoist-" + taskId + ".ics"
        if (objects[url] != null) throw SyncConflictException("$url already exists")
        return write(url, ics)
    }

    override fun update(href: String, ics: String, ifMatch: String?): WriteResult {
        if (ifMatch != null && objects[href]?.etag != ifMatch) {
            throw SyncConflictException("etag mismatch at $href")
        }
        return write(href, ics)
    }

    private fun write(url: String, ics: String): WriteResult {
        val etag = "\"etag-${++etagSeq}\""
        objects[url] = CalendarObject(url, etag, ics)
        return WriteResult(url, etag)
    }

    override fun delete(href: String, ifMatch: String?) {
        if (objects.remove(href) != null) etagSeq++
    }

    /** Simulates the user deleting every synced event in Calendar.app. */
    fun clearExternally() {
        objects.clear()
        etagSeq++
    }

    /** Simulates a user editing the event in Calendar.app: new content, new ETag. */
    fun editExternally(url: String, transform: (String) -> String) {
        val existing = requireNonNull(objects[url], url)
        objects[url] = existing.copy(
            data = transform(existing.data!!),
            etag = "\"etag-${++etagSeq}\"",
        )
    }

    private fun <T : Any> requireNonNull(value: T?, url: String): T =
        value ?: error("no calendar object at $url")
}

/** An in-memory Todoist that bumps `updated_at` on every write, as the real API does. */
class FakeTodoist(initial: List<TodoistTask> = emptyList()) : TaskSide {
    val tasks = linkedMapOf<String, TodoistTask>()
    private var idSeq = 100
    private var revision = 0

    init {
        initial.forEach { tasks[it.id] = it }
    }

    /** Set to model the completed-task lookup being unavailable. */
    var completedLookupFails = false

    /** Active tasks only, as the real endpoint is: completing one takes it off this list. */
    override fun listTasks(): List<TodoistTask> =
        tasks.values.filter { !it.isDeleted && !it.checked }

    override fun listCompleted(since: Instant, until: Instant): List<TodoistTask> {
        if (completedLookupFails) error("completed lookup is unavailable")
        return tasks.values.filter { task ->
            if (!task.checked || task.isDeleted) return@filter false
            val at = task.completedAt?.let { Instant.parse(it) } ?: return@filter true
            !at.isBefore(since) && at.isBefore(until)
        }
    }

    /** The real endpoint only ever returns an active task. */
    override fun getTask(id: String): TodoistTask? = tasks[id]?.takeIf { !it.checked }

    override fun create(
        content: String,
        description: String,
        projectId: String?,
        patch: TaskPatch,
    ): TodoistTask {
        val task = TodoistTask(
            id = (++idSeq).toString(),
            projectId = projectId ?: "inbox",
            content = content,
            description = description,
            updatedAt = stamp(),
        ).applyPatch(patch)
        tasks[task.id] = task
        return task
    }

    override fun update(id: String, patch: TaskPatch): TodoistTask {
        val updated = tasks.getValue(id).applyPatch(patch).copy(updatedAt = stamp())
        tasks[id] = updated
        return updated
    }

    override fun close(id: String) {
        tasks[id] =
            tasks.getValue(id).copy(checked = true, completedAt = COMPLETED_AT, updatedAt = stamp())
    }

    /** Simulates an edit made in the Todoist app. */
    fun editExternally(id: String, transform: (TodoistTask) -> TodoistTask) {
        tasks[id] = transform(tasks.getValue(id)).copy(updatedAt = stamp())
    }

    private fun stamp(): String = "2026-09-10T00:00:${"%02d".format(++revision)}Z"

    private fun TodoistTask.applyPatch(patch: TaskPatch): TodoistTask {
        val newDue = when {
            patch.dueDatetime != null -> TodoistDue(
                date = UTC.format(patch.dueDatetime.atZone(ZoneOffset.UTC)),
                isRecurring = due?.isRecurring ?: false,
            )

            patch.dueDate != null -> TodoistDue(
                date = patch.dueDate.toString(),
                isRecurring = due?.isRecurring ?: false,
            )

            else -> due
        }
        return copy(
            content = patch.content ?: content,
            description = patch.description ?: description,
            due = newDue,
            duration = patch.durationMinutes
                ?.let { TodoistDuration(it, "minute") }
                ?: if (patch.dueDate != null) null else duration,
        )
    }

    private companion object {
        val UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

        /** Just before the tests' fixed clock, so a close lands inside the lookback window. */
        const val COMPLETED_AT = "2026-09-10T11:59:00Z"
    }
}

/** Convenience builder for a task with a timed due date. */
fun task(
    id: String,
    content: String,
    due: String?,
    durationMinutes: Int? = null,
    priority: Int = 1,
    description: String = "",
    recurring: Boolean = false,
    checked: Boolean = false,
    updatedAt: String = "2026-09-01T00:00:00Z",
): TodoistTask = TodoistTask(
    id = id,
    projectId = "p1",
    content = content,
    description = description,
    priority = priority,
    due = due?.let { TodoistDue(date = it, isRecurring = recurring) },
    duration = durationMinutes?.let { TodoistDuration(it, "minute") },
    checked = checked,
    updatedAt = updatedAt,
)
