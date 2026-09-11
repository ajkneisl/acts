package dev.ajkneisl.acts.todoist.models

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TodoistDue(
    /** `2026-09-10`, `2026-09-10T15:00:00`, or `2026-09-10T15:00:00Z`. */
    val date: String = "",
    /** IANA zone for fixed-timezone tasks; null means the due time floats in the user's zone. */
    val timezone: String? = null,
    val string: String? = null,
    val lang: String? = null,
    @SerialName("is_recurring") val isRecurring: Boolean = false,
) {
    val hasTime: Boolean
        get() = date.contains('T')

    fun localDate(): LocalDate? = runCatching {
        LocalDate.parse(date.substringBefore('T'))
    }
        .getOrNull()

    /** Resolves the due moment, honouring an explicit timezone and otherwise [fallbackZone]. */
    fun instant(fallbackZone: ZoneId): Instant? {
        if (!hasTime) return null
        if (date.endsWith("Z")) {
            return runCatching { Instant.parse(date) }.getOrNull()
        }
        val zone = timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallbackZone
        return runCatching { LocalDateTime.parse(date).atZone(zone).toInstant() }.getOrNull()
    }
}
