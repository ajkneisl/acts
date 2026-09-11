package dev.ajkneisl.acts.sync

import dev.ajkneisl.acts.todoist.models.TaskPatch
import dev.ajkneisl.acts.todoist.models.TodoistTask

interface TaskSide {
    fun listTasks(): List<TodoistTask>

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
