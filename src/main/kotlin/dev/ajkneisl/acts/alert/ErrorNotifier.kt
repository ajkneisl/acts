package dev.ajkneisl.acts.alert

import dev.ajkneisl.acts.Resources
import dev.ajkneisl.acts.health.SyncHealth
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory

/** Emails sync failures, without turning an outage into a full mailbox. */
class ErrorNotifier(
    private val mailer: Mailer,
    private val cooldown: Duration,
    private val health: SyncHealth,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(ErrorNotifier::class.java)

    private var lastSentAt: Instant? = null
    private var alerting = false

    @Synchronized
    fun onFailure(message: String, consecutive: Int) {
        val now = Instant.now(clock)
        val quiet = lastSentAt?.let { Duration.between(it, now) < cooldown } ?: false
        if (alerting && quiet) return

        val subject = if (alerting) "acts is still failing" else "acts sync is failing"
        deliver(subject, failureBody(message, consecutive, now))
        alerting = true
        lastSentAt = now
    }

    @Synchronized
    fun onSuccess() {
        if (!alerting) return
        deliver("acts has recovered", recoveryBody())
        alerting = false
        lastSentAt = null
    }

    /**
     * Deliver a message.
     *
     * @param subject The subject of the email.
     * @param body The body of the email.
     */
    private fun deliver(subject: String, body: String) {
        runCatching { mailer.send(subject, body) }
            .onFailure { log.warn("Could not send the alert: {}", it.message) }
    }

    /**
     * The body of the email when the service fails.
     *
     * @param message The failure reasoning.
     * @param consecutive How many failures in a row have been seen.
     */
    private fun failureBody(message: String, consecutive: Int, now: Instant): String =
        Resources.template(
            "/text/alert-failure.txt",
            "error" to message,
            "consecutive" to consecutive.toString(),
            "at" to now.toString(),
            "lastSuccess" to (health.lastSuccess?.toString() ?: "never since starting"),
            "startedAt" to health.startedAt.toString(),
        )

    /** The body of the email when the service recovers. */
    private fun recoveryBody(): String =
        Resources.template(
            "/text/alert-recovery.txt",
            "recoveredAt" to Instant.now(clock).toString(),
            "startedAt" to health.startedAt.toString(),
        )
}
