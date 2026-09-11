package dev.ajkneisl.acts.todoist.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One page of a cursor-paginated Todoist collection. */
@Serializable
data class PaginatedList<T>(
    val results: List<T> = emptyList(),
    /** The completed-task endpoints spell the very same page `items`. */
    val items: List<T> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
) {
    val page: List<T>
        get() = results.ifEmpty { items }
}
