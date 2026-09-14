package dev.ajkneisl.acts.config

/** All settings. */
enum class Setting(val variable: String, val default: String? = null, val secret: Boolean = false) {
    APPLE_ID("ACTS_APPLE_ID"),
    APPLE_PASSWORD("ACTS_APPLE_PASSWORD", secret = true),
    CALENDAR_NAME("ACTS_CALENDAR_NAME", "Todoist"),
    CALDAV_URL("ACTS_CALDAV_URL", "https://caldav.icloud.com"),
    TODOIST_TOKEN("ACTS_TODOIST_TOKEN", secret = true),
    PROJECTS("ACTS_PROJECTS"),
    EXCLUDE_PROJECTS("ACTS_EXCLUDE_PROJECTS"),
    PAST_DAYS("ACTS_PAST_DAYS", "7"),
    FUTURE_DAYS("ACTS_FUTURE_DAYS", "60"),
    DEFAULT_DURATION_MINUTES("ACTS_DEFAULT_DURATION_MINUTES", "30"),
    CONFLICT_POLICY("ACTS_CONFLICT_POLICY", "TODOIST_WINS"),
    ON_EVENT_DELETED("ACTS_ON_EVENT_DELETED", "SUPPRESS"),
    CREATE_TASKS_FROM_EVENTS("ACTS_CREATE_TASKS_FROM_EVENTS", "true"),
    NEW_TASK_PROJECT_ID("ACTS_NEW_TASK_PROJECT_ID"),
    INTERVAL_SECONDS("ACTS_INTERVAL_SECONDS", "300"),
    CALENDAR_POLL_SECONDS("ACTS_CALENDAR_POLL_SECONDS", "15"),
    TIMEZONE("ACTS_TIMEZONE"),

    //
    // http settings
    //

    /** The webhook and the healthcheck share one server, and so one port. */
    HTTP_PORT("ACTS_HTTP_PORT", "8080"),

    //
    // webhook settings
    //

    WEBHOOK_ENABLED("ACTS_WEBHOOK_ENABLED", "false"),
    WEBHOOK_PATH("ACTS_WEBHOOK_PATH", "/todoist"),
    TODOIST_CLIENT_SECRET("ACTS_TODOIST_CLIENT_SECRET", secret = true),

    //
    // health settings
    //

    HEALTH_ENABLED("ACTS_HEALTH_ENABLED", "false"),
    HEALTH_PATH("ACTS_HEALTH_PATH", "/health"),
    STATE_PATH("ACTS_STATE"),
}
