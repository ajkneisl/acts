package dev.ajkneisl.acts.sync

import dev.ajkneisl.acts.todoist.models.TaskPatch
import dev.ajkneisl.acts.todoist.models.TodoistTask
import java.time.Instant

interface TaskSide {
    /** Active tasks only: Todoist drops a task from this list the moment it is completed. */
    fun listTasks(): List<TodoistTask>

    /** Tasks completed in the given window, which the active list can never show. */
    fun listCompleted(since: Instant, until: Instant): List<TodoistTask>

    fun getTask(id: String): TodoistTask?

    fun create(
        content: String,
        description: String,
        projectId: String?,
        patch: TaskPatch,
    ): TodoistTask

    fun update(id: String, patch: TaskPatch): TodoistTask

    fun close(id: String)
}
