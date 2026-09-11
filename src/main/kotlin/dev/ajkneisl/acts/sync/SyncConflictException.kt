package dev.ajkneisl.acts.sync

/** Raised when the calendar copy changed under us, whichever backend noticed. */
class SyncConflictException(message: String) : RuntimeException(message)
