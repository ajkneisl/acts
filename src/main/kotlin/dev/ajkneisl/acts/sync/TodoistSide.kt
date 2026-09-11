package dev.ajkneisl.acts.sync

import dev.ajkneisl.acts.config.Setting
import dev.ajkneisl.acts.config.Settings
import dev.ajkneisl.acts.todoist.TodoistClient
import dev.ajkneisl.acts.todoist.models.TaskPatch
import dev.ajkneisl.acts.todoist.models.TodoistTask

class TodoistSide(
    private val client: TodoistClient,
    private val settings: Settings,
) : TaskSide {

    private val log = org.slf4j.LoggerFactory.getLogger(TodoistSide::class.java)

    /** Fetched once per process; project lists change far more slowly than tasks. */
    private val projects by lazy {
        client.listProjects().filter { !it.isArchived && !it.isDeleted }
    }

    /** Ids or names. Null means unconfigured, so a typo cannot widen the sync. */
    private fun idsFor(refs: List<String>): Set<String>? {
        if (refs.isEmpty()) return null
        val byId = projects.associateBy { it.id }
        val byName = projects.associateBy { it.name.lowercase() }
        return refs.mapNotNullTo(mutableSetOf()) { ref ->
            val hit = byId[ref] ?: byName[ref.lowercase()]
            if (hit == null) {
                log.warn(
                    "No Todoist project matches '{}'. Known projects: {}",
                    ref,
                    projects.joinToString(", ") { it.name },
                )
            }
            hit?.id
        }
    }

    override fun listTasks(): List<TodoistTask> {
        val include = idsFor(settings.list(Setting.PROJECTS))
        val exclude = idsFor(settings.list(Setting.EXCLUDE_PROJECTS)).orEmpty()
        if (include == null && exclude.isEmpty()) return client.listTasks()
        return client.listTasks().filter { task ->
            (include == null || task.projectId in include) && task.projectId !in exclude
        }
    }

    override fun getTask(id: String): TodoistTask? = client.getTask(id)

    override fun create(
        content: String,
        description: String,
        projectId: String?,
        patch: TaskPatch,
    ): TodoistTask = client.createTask(content, description, projectId, patch)

    override fun update(id: String, patch: TaskPatch): TodoistTask = client.updateTask(id, patch)

    override fun close(id: String) = client.closeTask(id)
}
