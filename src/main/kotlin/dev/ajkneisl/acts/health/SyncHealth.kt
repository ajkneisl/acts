package dev.ajkneisl.acts.health

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** The health information of the sync. */
class SyncHealth {
    /** When the sync started at. */
    val startedAt: Instant = Instant.now()

    /** The time of the last successful sync. */
    @Volatile
    var lastSuccess: Instant? = null
        private set

    /** The time of the last failed sync. */
    @Volatile
    var lastFailure: Instant? = null
        private set

    /** The time of the last error. */
    @Volatile
    var lastError: String? = null
        private set

    /** The amount of fails. */
    private val failures = AtomicInteger()

    /** The amount of fails in a row. */
    val consecutiveFailures: Int
        get() = failures.get()

    /** If the service as a whole is healthy.. */
    val healthy: Boolean
        get() = failures.get() < UNHEALTHY_AFTER

    /** Record a successful sync. */
    fun recordSuccess() {
        lastSuccess = Instant.now()
        failures.set(0)
        lastError = null
    }

    /** Record a failed sync. */
    fun recordFailure(message: String): Int {
        lastFailure = Instant.now()
        lastError = message
        return failures.incrementAndGet()
    }

    companion object {
        /** How many failed syncs means it's not healthy. */
        const val UNHEALTHY_AFTER = 3
    }
}
