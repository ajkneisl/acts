package dev.ajkneisl.acts.todoist.models

import java.time.Instant
import java.time.LocalDate

/** Fields we are willing to write back to Todoist from a calendar edit. */
data class TaskPatch(
    val content: String? = null,
    val description: String? = null,
    val dueDate: LocalDate? = null,
    val dueDatetime: Instant? = null,
    val durationMinutes: Int? = null,
)
