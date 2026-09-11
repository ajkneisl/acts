package dev.ajkneisl.acts.sync.models

import kotlinx.serialization.Serializable

/** What we knew about one task and its event at the end of the last sync. */
@Serializable
data class Link(
    val taskId: String,
    val href: String,
    val etag: String? = null,
    val todoistUpdatedAt: String? = null,
    /** Canonical fingerprint of the agreed-upon content at last sync. */
    val fingerprint: String? = null,
    /**
     * Set when the user deleted the event and the policy is to leave it deleted. Cleared as soon as
     * the task itself changes, so a later edit in Todoist brings the block back.
     */
    val suppressed: Boolean = false,
    val lastSyncedAt: String? = null,
)
