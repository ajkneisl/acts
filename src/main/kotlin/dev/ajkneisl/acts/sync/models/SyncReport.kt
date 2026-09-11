package dev.ajkneisl.acts.sync.models

import dev.ajkneisl.acts.sync.SyncState

data class SyncReport(val actions: List<SyncAction>, val state: SyncState) {
    fun count(kind: ActionKind): Int = actions.count { it.kind == kind }

    val changed: Boolean
        get() = actions.any { it.kind != ActionKind.SKIPPED }

    fun summary(): String {
        if (actions.isEmpty()) return "Already in sync."
        return ActionKind.entries
            .mapNotNull { kind ->
                count(kind).takeIf { it > 0 }?.let { "${kind.name.lowercase()}: $it" }
            }
            .joinToString(", ")
    }
}
