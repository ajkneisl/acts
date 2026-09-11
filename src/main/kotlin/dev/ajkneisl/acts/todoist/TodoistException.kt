package dev.ajkneisl.acts.todoist

/** A Todoist API call that failed, carrying the HTTP status when there was one. */
class TodoistException(message: String, val status: Int = -1) : RuntimeException(message)
