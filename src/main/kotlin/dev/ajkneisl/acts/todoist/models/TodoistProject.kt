package dev.ajkneisl.acts.todoist.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TodoistProject(
    val id: String,
    val name: String = "",
    @SerialName("is_archived") val isArchived: Boolean = false,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("inbox_project") val inboxProject: Boolean = false,
)
