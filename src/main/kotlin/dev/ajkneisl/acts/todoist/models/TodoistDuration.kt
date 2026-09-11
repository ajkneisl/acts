package dev.ajkneisl.acts.todoist.models

import kotlinx.serialization.Serializable

@Serializable
data class TodoistDuration(
    val amount: Int = 0,
    /** `minute` or `day`. */
    val unit: String = "minute",
) {
    fun minutes(): Int = if (unit.equals("day", ignoreCase = true)) amount * 24 * 60 else amount
}
