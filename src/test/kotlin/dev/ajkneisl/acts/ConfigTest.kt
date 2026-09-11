package dev.ajkneisl.acts

import dev.ajkneisl.acts.config.ConflictPolicy
import dev.ajkneisl.acts.config.DeletedEventPolicy
import dev.ajkneisl.acts.config.EnvLookup
import dev.ajkneisl.acts.config.Setting
import dev.ajkneisl.acts.config.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {

    private fun env(vararg pairs: Pair<Setting, String>): Settings {
        val byVariable = pairs.associate { (setting, value) -> setting.variable to value }
        return Settings.fromEnvironment { byVariable[it] }
    }

    private val empty = Settings.fromEnvironment { null }

    @Test
    fun `an empty environment gives the defaults`() {
        assertEquals("Todoist", empty.text(Setting.CALENDAR_NAME))
        assertEquals(300L, empty.long(Setting.INTERVAL_SECONDS))
        assertEquals(15L, empty.long(Setting.CALENDAR_POLL_SECONDS))
        assertEquals(false, empty.bool(Setting.WEBHOOK_ENABLED))
        assertEquals("https://caldav.icloud.com", empty.text(Setting.CALDAV_URL))
    }

    @Test
    fun `a setting with no default and no value is null`() {
        assertNull(empty[Setting.APPLE_ID])
        assertNull(empty[Setting.TIMEZONE])
        assertEquals(emptyList(), empty.list(Setting.PROJECTS))
    }

    @Test
    fun `the environment is read for every setting`() {
        val settings =
            env(
                Setting.APPLE_ID to "you@example.com",
                Setting.CALENDAR_NAME to "Tasks",
                Setting.PROJECTS to "Work, Home",
                Setting.PAST_DAYS to "3",
                Setting.CONFLICT_POLICY to "newest_wins",
                Setting.ON_EVENT_DELETED to "COMPLETE_TASK",
                Setting.CREATE_TASKS_FROM_EVENTS to "false",
                Setting.WEBHOOK_PORT to "9000",
                Setting.SES_TO to "a@example.com,b@example.com",
            )

        assertEquals("you@example.com", settings[Setting.APPLE_ID])
        assertEquals("Tasks", settings.text(Setting.CALENDAR_NAME))
        assertEquals(listOf("Work", "Home"), settings.list(Setting.PROJECTS))
        assertEquals(3L, settings.long(Setting.PAST_DAYS))
        assertEquals(ConflictPolicy.NEWEST_WINS, settings.enum<ConflictPolicy>(Setting.CONFLICT_POLICY))
        assertEquals(
            DeletedEventPolicy.COMPLETE_TASK,
            settings.enum<DeletedEventPolicy>(Setting.ON_EVENT_DELETED),
        )
        assertEquals(false, settings.bool(Setting.CREATE_TASKS_FROM_EVENTS))
        assertEquals(9000, settings.int(Setting.WEBHOOK_PORT))
        assertEquals(listOf("a@example.com", "b@example.com"), settings.list(Setting.SES_TO))
    }

    @Test
    fun `a blank value falls back to the default`() {
        // Compose writes an empty string for a variable that was never set, and that has to read
        // as "unset" rather than as an empty setting.
        val settings = env(Setting.WEBHOOK_PATH to "   ", Setting.CALENDAR_NAME to "")

        assertEquals("/todoist-webhook", settings.text(Setting.WEBHOOK_PATH))
        assertEquals("Todoist", settings.text(Setting.CALENDAR_NAME))
    }

    @Test
    fun `values are trimmed`() {
        assertEquals("Tasks", env(Setting.CALENDAR_NAME to "  Tasks  ").text(Setting.CALENDAR_NAME))
    }

    @Test
    fun `enums are matched case-insensitively`() {
        val settings = env(Setting.CONFLICT_POLICY to "calendar_wins")
        assertEquals(ConflictPolicy.CALENDAR_WINS, settings.enum<ConflictPolicy>(Setting.CONFLICT_POLICY))
    }

    @Test
    fun `a list tolerates spacing and trailing separators`() {
        assertEquals(listOf("Work", "Home"), env(Setting.PROJECTS to " Work , , Home ,").list(Setting.PROJECTS))
    }

    @Test
    fun `an unparseable number is an error, not a silent fallback`() {
        // Silently syncing on the default interval because of a typo is the kind of thing nobody
        // notices for weeks.
        val failure =
            assertFailsWith<IllegalStateException> {
                env(Setting.INTERVAL_SECONDS to "6O").long(Setting.INTERVAL_SECONDS)
            }
        assertTrue(failure.message!!.contains(Setting.INTERVAL_SECONDS.variable), failure.message!!)
        assertTrue(failure.message!!.contains("6O"), failure.message!!)
    }

    @Test
    fun `an unparseable boolean is an error`() {
        assertFailsWith<IllegalStateException> {
            env(Setting.WEBHOOK_ENABLED to "yes").bool(Setting.WEBHOOK_ENABLED)
        }
    }

    @Test
    fun `an unknown enum names the ones that exist`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                env(Setting.CONFLICT_POLICY to "whoever_shouts_loudest")
                    .enum<ConflictPolicy>(Setting.CONFLICT_POLICY)
            }
        assertTrue(failure.message!!.contains("TODOIST_WINS"), failure.message!!)
    }

    @Test
    fun `a missing credential explains where to get one`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                empty.credential(Setting.APPLE_PASSWORD, "/text/error-apple-password.txt")
            }
        assertTrue(failure.message!!.contains(Setting.APPLE_PASSWORD.variable), failure.message!!)
    }

    @Test
    fun `the summary never prints a secret`() {
        val settings = env(Setting.APPLE_PASSWORD to "hunter2", Setting.CALENDAR_NAME to "Tasks")
        val summary = settings.summary()

        assertTrue(!summary.contains("hunter2"), "the summary leaked a credential")
        assertTrue(summary.contains("(set)"), summary)
        assertTrue(summary.contains("Tasks"), "non-secret values should still be visible")
    }

    @Test
    fun `every setting has a distinct variable name`() {
        val names = Setting.entries.map { it.variable }
        assertEquals(names.size, names.toSet().size, "two settings share a variable name")
        assertTrue(names.all { it.startsWith("ACTS_") }, names.toString())
    }

    @Test
    fun `of applies overrides over the defaults`() {
        val settings = Settings.of(Setting.INTERVAL_SECONDS to "60")
        assertEquals(60L, settings.long(Setting.INTERVAL_SECONDS))
        assertEquals("Todoist", settings.text(Setting.CALENDAR_NAME))
    }
}
