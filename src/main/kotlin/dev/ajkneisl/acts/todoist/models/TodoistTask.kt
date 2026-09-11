package dev.ajkneisl.acts.todoist.models

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TodoistTask(
    val id: String,
    @SerialName("project_id") val projectId: String = "",
    @SerialName("section_id") val sectionId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val content: String = "",
    val description: String = "",
    val priority: Int = 1,
    val labels: List<String> = emptyList(),
    val due: TodoistDue? = null,
    val duration: TodoistDuration? = null,
    val deadline: JsonElement? = null,
    val checked: Boolean = false,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("added_at") val addedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
) {
    val isRecurring: Boolean
        get() = due?.isRecurring == true

    val webUrl: String
        get() = "https://app.todoist.com/app/task/$id"

    /** A cheap "something moved" signal, confirmed by a fingerprint. */
    fun updatedAtInstant(): Instant? = updatedAt?.let { raw ->
        runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) }.getOrNull()
    }
}
