package dev.ajkneisl.acts.sync.models

/** One thing a pass did, in terms a user can read back. */
data class SyncAction(val kind: ActionKind, val subject: String, val detail: String = "")
