package dev.ajkneisl.acts.sync.models

/** What a pass did to one task/event pair. */
enum class ActionKind {
    CREATE_EVENT,
    UPDATE_EVENT,
    DELETE_EVENT,
    CREATE_TASK,
    UPDATE_TASK,
    COMPLETE_TASK,
    SKIPPED,
    CONFLICT,
}
