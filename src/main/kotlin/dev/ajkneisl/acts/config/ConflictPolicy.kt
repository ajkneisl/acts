package dev.ajkneisl.acts.config

/** How to resolve a change that landed on both sides since the last sync. */
enum class ConflictPolicy {
    TODOIST_WINS,
    CALENDAR_WINS,
    NEWEST_WINS,
}
