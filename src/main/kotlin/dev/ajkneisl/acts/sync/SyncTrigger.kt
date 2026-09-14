package dev.ajkneisl.acts.sync

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * A wake-up signal from outside the watch loop. This occurs from
 * [dev.ajkneisl.acts.todoist.webhook.TodoistWebhookEndpoint]
 */
class SyncTrigger {
    private val permits = Semaphore(0)

    /** Called from the webhook thread on an event. */
    fun fire() {
        permits.release()
    }

    /** Waits up to [millis] for a signal. Returns true if one arrived, false on timeout. */
    fun awaitFor(millis: Long): Boolean {
        if (millis <= 0) return drainIfSignalled()

        val woken = permits.tryAcquire(millis, TimeUnit.MILLISECONDS)
        if (woken) permits.drainPermits()

        return woken
    }

    /** Waits out a burst. */
    fun settle(millis: Long) {
        if (millis > 0) Thread.sleep(millis)

        permits.drainPermits()
    }

    private fun drainIfSignalled(): Boolean {
        val woken = permits.tryAcquire()
        if (woken) permits.drainPermits()
        return woken
    }
}
