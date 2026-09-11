package dev.ajkneisl.acts.config

/** What it means when a synced event disappears from the Apple calendar. */
enum class DeletedEventPolicy {
    /** Leave the task alone and stop re-creating the event until the task changes again. */
    SUPPRESS,

    /** Treat the deletion as an accident and put the event back. */
    RECREATE,

    /** Treat removing the block as finishing the work. */
    COMPLETE_TASK,
}
