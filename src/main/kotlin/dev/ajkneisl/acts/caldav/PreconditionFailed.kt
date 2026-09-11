package dev.ajkneisl.acts.caldav

/** The server copy changed under us: an `If-Match` precondition was refused. */
class PreconditionFailed(message: String) : CalDavException(message, 412)
