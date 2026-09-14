package dev.ajkneisl.acts

import dev.ajkneisl.acts.caldav.CalDavClient
import dev.ajkneisl.acts.config.Setting
import dev.ajkneisl.acts.config.Settings
import dev.ajkneisl.acts.http.health.HealthEndpoint
import dev.ajkneisl.acts.http.health.SyncHealth
import dev.ajkneisl.acts.http.HttpHost
import dev.ajkneisl.acts.sync.CalDavSide
import dev.ajkneisl.acts.sync.SyncEngine
import dev.ajkneisl.acts.sync.SyncState
import dev.ajkneisl.acts.sync.SyncTrigger
import dev.ajkneisl.acts.sync.TodoistSide
import dev.ajkneisl.acts.sync.models.ActionKind
import dev.ajkneisl.acts.sync.models.SyncReport
import dev.ajkneisl.acts.todoist.TodoistClient
import dev.ajkneisl.acts.todoist.webhook.TodoistWebhookEndpoint
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZoneId
import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

/** Floors that keep a mis-set config from hammering either service. */
private const val MIN_CALENDAR_POLL_SECONDS = 5L
private const val MIN_FULL_PASS_SECONDS = 30L
private const val MIN_SLEEP_MILLIS = 1_000L

/** Lets a burst of webhooks from one bulk edit collapse into a single pass. */
private const val WEBHOOK_SETTLE_MILLIS = 750L
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NANOS_PER_MILLI = 1_000_000L

private val log = LoggerFactory.getLogger("ACTS")

fun main() {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")

    try {
        watch()
    } catch (ex: Exception) {
        ex.printStackTrace()

        exitProcess(1)
    }
}

/** Where the link store lives. Data, not configuration, but still worth relocating. */
private fun statePath(settings: Settings): Path =
    settings[Setting.STATE_PATH]?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home"), ".local", "state", "acts", "state.json")

private fun buildEngine(settings: Settings, state: SyncState): Pair<SyncEngine, String> {
    val dav =
        CalDavClient(
            settings.credential(Setting.APPLE_ID, "/text/error-apple-id.txt"),
            settings.credential(Setting.APPLE_PASSWORD, "/text/error-apple-password.txt"),
        )
    val resolved = CalendarResolver.resolve(dav, settings, state.calendarUrl)
    val todoist =
        TodoistClient(settings.credential(Setting.TODOIST_TOKEN, "/text/error-todoist-token.txt"))
    val engine =
        SyncEngine(
            tasks = TodoistSide(todoist, settings),
            calendar = CalDavSide(dav, resolved.calendarUrl),
            settings = settings,
            zone = resolveZone(settings, todoist),
        )

    return engine to resolved.calendarUrl
}

/** Resolve a Timezone. */
private fun resolveZone(settings: Settings, todoist: TodoistClient): ZoneId {
    settings[Setting.TIMEZONE]?.let { name ->
        return runCatching { ZoneId.of(name) }
            .getOrElse { settings.invalid(Setting.TIMEZONE, "a known IANA timezone id") }
    }

    return todoist.userTimezone() ?: ZoneId.systemDefault()
}

private fun printReport(report: SyncReport) {
    for ((kind, subject, details) in report.actions) {
        val marker =
            when (kind) {
                ActionKind.CONFLICT -> "!"
                ActionKind.SKIPPED -> "-"
                else -> "+"
            }
        val label = kind.name.lowercase().replace('_', ' ')
        val detail = details.let { if (it.isEmpty()) "" else "  ($it)" }
        println("$marker $label: $subject$detail")
    }
    println()
    println(report.summary())
}

private fun watch() {
    val settings = Settings.fromEnvironment()
    val statePath = statePath(settings)
    var state = SyncState.load(statePath)

    val fullEvery = settings.long(Setting.INTERVAL_SECONDS).coerceAtLeast(MIN_FULL_PASS_SECONDS)
    val pollEvery =
        settings
            .long(Setting.CALENDAR_POLL_SECONDS)
            .coerceAtLeast(MIN_CALENDAR_POLL_SECONDS)
            .coerceAtMost(fullEvery)

    val trigger = SyncTrigger()
    val health = SyncHealth()

    // Both endpoints live on one server, so there is a single port to publish and tunnel.
    val webhookEnabled = settings.bool(Setting.WEBHOOK_ENABLED)
    val healthEnabled = settings.bool(Setting.HEALTH_ENABLED)
    val http =
        if (webhookEnabled || healthEnabled) {
            HttpHost(settings.int(Setting.HTTP_PORT)).apply {
                if (webhookEnabled) {
                    mount(
                        TodoistWebhookEndpoint(
                            path = settings.text(Setting.WEBHOOK_PATH),
                            clientSecret =
                                settings.credential(
                                    Setting.TODOIST_CLIENT_SECRET,
                                    "/text/error-webhook-secret.txt",
                                ),
                            trigger = trigger,
                        )
                    )
                }
                if (healthEnabled) {
                    mount(HealthEndpoint(settings.text(Setting.HEALTH_PATH), health))
                }
                start()
            }
        } else {
            null
        }

    Runtime.getRuntime().addShutdownHook(Thread { http?.close() })

    log.info("Watching calendar every {}s, full check every {}s", pollEvery, fullEvery)

    var engine: SyncEngine? = null
    var calendarUrl: String? = null
    var lastFullPass = 0L
    var todoistSignalled = false

    while (true) {
        val startedAt = System.nanoTime()

        try {
            if (engine == null) {
                val built = buildEngine(settings, state)
                engine = built.first
                calendarUrl = built.second
            }

            // A webhook means Todoist moved, which only a full pass can see.
            val dueForFullPass =
                todoistSignalled ||
                    lastFullPass == 0L ||
                    (startedAt - lastFullPass) / NANOS_PER_SECOND >= fullEvery
            todoistSignalled = false

            // Only worth probing when we were not about to do the work anyway.
            val probed = if (dueForFullPass) null else engine.calendarToken()
            val calendarMoved = probed != null && probed != state.calendarCtag

            if (dueForFullPass || calendarMoved) {
                // Hand the pass the token we just read rather than making it ask again.
                val report = engine.sync(state, dryRun = false, knownToken = probed)
                // Staying silent on a quiet pass keeps a short interval readable.
                if (report.actions.isNotEmpty()) printReport(report)
                state = report.state.copy(calendarUrl = calendarUrl)
                SyncState.save(statePath, state)
                lastFullPass = System.nanoTime()
            }
            health.recordSuccess()
        } catch (e: Exception) {
            // A transient network or iCloud hiccup should not end the daemon.
            val message = e.message ?: e::class.simpleName.orEmpty()
            System.err.println("sync failed: $message")
            health.recordFailure(message)
        }

        // Wait out the rest of the tick, but let a webhook cut it short.
        val elapsedMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        val remaining = (pollEvery * 1000 - elapsedMillis).coerceAtLeast(MIN_SLEEP_MILLIS)
        if (trigger.awaitFor(remaining)) {
            trigger.settle(WEBHOOK_SETTLE_MILLIS)
            todoistSignalled = true
        }
    }
}
